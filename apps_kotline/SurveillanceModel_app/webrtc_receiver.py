import sys
import asyncio
import cv2
import numpy as np
import httpx
from aiortc import RTCPeerConnection, RTCSessionDescription, MediaStreamError
from aiortc.contrib.media import MediaRelay

# --- CONFIGURATION ---
ANDROID_IP = "10.188.226.29"
SIGNALING_URL = f"http://{ANDROID_IP}:8888"
DISP_WIDTH = 1920
DISP_HEIGHT = 1080
# ---------------------

relay = MediaRelay()

class WebRtcReceiver:
    def __init__(self):
        self.pc = None
        self.context_frame = None
        self.source_frame = None
        self.running = True
        self.connected = False
        self.channel = None
        self.fps = {"context": 0.0, "source": 0.0}

    async def connect(self):
        if self.pc:
            await self.pc.close()
        
        self.pc = RTCPeerConnection()
        self.connected = False
        self.channel = self.pc.createDataChannel("commands")

        @self.channel.on("open")
        def on_open():
            print("Command DataChannel opened.")

        @self.pc.on("track")
        def on_track(track):
            print(f"Track received: {track.kind} (ID: {track.id})")
            if track.kind == "video":
                asyncio.ensure_future(self.handle_track(track))

        self.pc.addTransceiver("video", direction="recvonly")
        self.pc.addTransceiver("video", direction="recvonly")
        
        offer = await self.pc.createOffer()
        await self.pc.setLocalDescription(offer)

        async with httpx.AsyncClient(timeout=5.0) as client:
            try:
                response = await client.post(SIGNALING_URL, content=self.pc.localDescription.sdp)
                if response.status_code == 200:
                    answer = RTCSessionDescription(sdp=response.text, type="answer")
                    await self.pc.setRemoteDescription(answer)
                    print("Connection established via signaling.")
                    self.connected = True
                    return True
                else:
                    print(f"Signaling failed: Received HTTP {response.status_code} from {SIGNALING_URL}")
            except httpx.ConnectError:
                print(f"Signaling error: Could not connect to {SIGNALING_URL}. Is the Android app running and on the same network?")
            except Exception as e:
                print(f"Signaling error to {SIGNALING_URL}: {type(e).__name__}: {e}")
                import traceback
                traceback.print_exc()
        return False

    def send_command(self, cmd):
        if self.channel and self.channel.readyState == "open":
            import json
            self.channel.send(json.dumps(cmd))
            print(f"Sent command: {cmd}")
            # Clear high-res buffer when mode changes to ensure clean transition
            if "mode" in cmd:
                self.source_frame = None
        else:
            print("DataChannel not open.")

    async def input_handler(self):
        print("Terminal Input Handler Started. Type command then Enter:")
        print("  mode <LOW|HIGH|VISION|HYBRID>  (or just 1, 2, 3, 4)")
        print("  classes <class1,class2>")
        print("  rect <x1,y1,x2,y2>")
        print("  q (to quit)")
        
        loop = asyncio.get_event_loop()
        while self.running:
            # Read line asynchronously from stdin
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if not line: break
            
            line = line.strip().lower()
            if not line: continue
            
            if line == 'q':
                self.running = False
                break
            elif line.startswith("mode "):
                mode = line.split(" ", 1)[1].upper()
                self.send_command({"mode": mode})
            elif line.startswith("classes "):
                cls = line.split(" ", 1)[1]
                self.send_command({"classes": cls})
            elif line.startswith("rect "):
                try:
                    rect_str = line.split(" ", 1)[1]
                    x1, y1, x2, y2 = map(float, rect_str.split(","))
                    self.send_command({"hybrid_rect": {"x1": x1, "y1": y1, "x2": x2, "y2": y2}})
                except: print("Format: rect x1,y1,x2,y2")
            elif line in ['1','2','3','4']:
                modes = ['LOW', 'HIGH', 'VISION', 'HYBRID']
                self.send_command({"mode": modes[int(line)-1]})
            else:
                print(f"Unknown command: '{line}'. Use: mode, classes, rect, or 1-4")

    async def run(self):
        # Setup Windows once
        self.context_frame = None
        self.source_frame = None
        self.mask_frame = None

        # Initial connection
        if not await self.connect():
            return

        # Start input handler task
        asyncio.create_task(self.input_handler())

        win_comp = "Composite (Final)"
        win_high = "Source (High Res)"
        
        cv2.namedWindow(win_comp, cv2.WINDOW_NORMAL)
        cv2.namedWindow(win_high, cv2.WINDOW_NORMAL)
        
        cv2.resizeWindow(win_comp, 960, 540) # Half size for display
        cv2.resizeWindow(win_high, 960, 540) # Half size for preview

        print("Receiver loop started. Waiting for Android stream...")

        try:
            while self.running:
                if not self.connected:
                    print("Reconnecting...")
                    await self.connect()
                    await asyncio.sleep(1)
                    continue

                # UI Update logic
                if self.source_frame is not None:
                    img = self.source_frame.copy()
                    cv2.putText(img, f"FPS: {self.fps['source']:.1f}", (10, 40), 
                                cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)
                    cv2.imshow(win_high, img)

                if self.context_frame is not None:
                    # Base context
                    combined_frame = cv2.resize(self.context_frame, (DISP_WIDTH, DISP_HEIGHT))
                    
                    # Blend the high resolution patches over it
                    if self.source_frame is not None:
                        if self.source_frame.shape[:2] != (DISP_HEIGHT, DISP_WIDTH):
                            src_final = cv2.resize(self.source_frame, (DISP_WIDTH, DISP_HEIGHT))
                        else:
                            src_final = self.source_frame

                        if combined_frame.shape == src_final.shape:
                            # 1. Mask threshold to eliminate WebRTC compression ringing in the black background
                            gray = cv2.cvtColor(src_final, cv2.COLOR_BGR2GRAY)
                            _, bin_mask = cv2.threshold(gray, 18, 255, cv2.THRESH_BINARY)
                            
                            # 2. Erode deeply so the gradient falls entirely within the valid high-res area
                            kernel = np.ones((25, 25), np.uint8)
                            eroded = cv2.erode(bin_mask, kernel, iterations=1)
                            
                            # 3. Apply heavy blur to create a feathered alpha mask
                            feathered_alpha = cv2.GaussianBlur(eroded, (31, 31), 0)
                            
                            # 4. CRITICAL FIX: Cut off any blur that bleeds into the black background
                            # This prevents "shadows" from blending black pixels onto the context stream.
                            feathered_alpha = cv2.bitwise_and(feathered_alpha, bin_mask)
                            
                            # 5. Fast partitioned alpha blending (only compute math on non-black pixels)
                            active_mask = feathered_alpha > 0
                            if active_mask.any():
                                alpha_active = feathered_alpha[active_mask].astype(np.uint16)[..., np.newaxis]
                                inv_alpha_active = 255 - alpha_active
                                
                                src_active = src_final[active_mask].astype(np.uint16)
                                ctx_active = combined_frame[active_mask].astype(np.uint16)
                                
                                combined_frame[active_mask] = ((src_active * alpha_active + ctx_active * inv_alpha_active) // 255).astype(np.uint8)

                    # Draw the final composite frame
                    cv2.putText(combined_frame, f"Context FPS: {self.fps['context']:.1f}", (10, 30), 
                                cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)
                    
                    # DEBUG OVERLAY: Show raw context frame in lower-left corner
                    if self.context_frame is not None:
                        raw_ctx = cv2.resize(self.context_frame, (320, 180))
                        h, w = combined_frame.shape[:2]
                        combined_frame[h-180:h, 0:320] = raw_ctx
                        cv2.rectangle(combined_frame, (0, h-180), (320, h), (0, 255, 0), 2)
                        cv2.putText(combined_frame, "RAW CONTEXT", (5, h-160), 
                                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)

                    cv2.imshow(win_comp, combined_frame)
                
                if cv2.waitKey(1) & 0xFF == ord('q'):
                    self.running = False
                
                await asyncio.sleep(0.01)

        except Exception as e:
            print(f"Receiver loop error: {e}")
            import traceback
            traceback.print_exc()
        finally:
            if self.pc:
                await self.pc.close()
            cv2.destroyAllWindows()

    async def handle_track(self, track):
        track_id = track.id
        print(f"Starting track handler for ID: {track_id}")
        source_track = relay.subscribe(track)
        
        # Track-specific FPS counters
        f_count = 0
        last_log = asyncio.get_event_loop().time()

        while self.running and self.connected:
            try:
                frame = await source_track.recv()
                img = frame.to_ndarray(format="bgr24")
                
                f_count += 1
                now = asyncio.get_event_loop().time()
                if now - last_log >= 2.0: # Update faster (every 2s)
                    fps = f_count / 2.0
                    if "context" in track_id.lower():
                        self.fps["context"] = fps
                    elif "source" in track_id.lower():
                        self.fps["source"] = fps
                    else:
                        h, w = img.shape[:2]
                        if w < 600: self.fps["context"] = fps
                        else: self.fps["source"] = fps
                    
                    f_count = 0
                    last_log = now

                # Robust identification
                if "context" in track_id.lower():
                    self.context_frame = img
                elif "source" in track_id.lower():
                    self.source_frame = img
                else:
                    # Fallback identification by size
                    h, w = img.shape[:2]
                    if w < 600:
                        self.context_frame = img
                    else:
                        self.source_frame = img
            except MediaStreamError as e:
                print(f"Track {track_id} stream error (connection lost?): {e}")
                self.connected = False 
                break
            except Exception as e:
                print(f"Track error on {track_id}: {type(e).__name__}: {e}")
                self.connected = False
                break

if __name__ == "__main__":
    receiver = WebRtcReceiver()
    try:
        asyncio.run(receiver.run())
    except KeyboardInterrupt:
        pass
