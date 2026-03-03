import cv2
from flask import Flask, Response, request
import threading
import io
import time
import subprocess
import socket
import json
import numpy as np
from ultralytics import YOLO

app = Flask(__name__)

# Global variables
latest_frame = None
latest_low_res = None
lock = threading.Lock()
# ROI: [x, y, w, h] in high-res coordinates (1280x720)
current_roi = [440, 260, 400, 400] 
# Track the resolution of the *actual* encoded stream to avoid constant resets
last_stream_res = (0, 0) # (w, h)
roi_lock = threading.Lock()

SAMPLE_VIDEO = "sample.mp4" # Set to filename to play video instead of camera

TRACK_MODE = "ALL"
DEBUG = False
BW_LOG = True

print("Loading YOLO11n object detection model...")
model = YOLO('yolo11n.pt')

# Reusable UDP socket for feedback
feedback_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

class ROIKalman:
    def __init__(self):
        self.kf = cv2.KalmanFilter(8, 4)
        self.kf.transitionMatrix = np.eye(8, dtype=np.float32)
        self.kf.measurementMatrix = np.zeros((4, 8), dtype=np.float32)
        for i in range(4): self.kf.measurementMatrix[i, i] = 1
        self.kf.processNoiseCov = np.eye(8, dtype=np.float32) * 1e-2
        self.kf.measurementNoiseCov = np.eye(4, dtype=np.float32) * 1e-1
        self.kf.errorCovPost = np.eye(8, dtype=np.float32)
        self.last_time = time.time()
        self.initialized = False

    def update(self, measurement):
        now = time.time()
        dt = now - self.last_time
        self.last_time = now
        for i in range(4): self.kf.transitionMatrix[i, i+4] = dt
        
        if not self.initialized:
            self.kf.statePost = np.array([measurement[0], measurement[1], measurement[2], measurement[3], 0, 0, 0, 0], dtype=np.float32)
            self.initialized = True
            return measurement
        self.kf.predict()
        self.kf.correct(np.array(measurement, dtype=np.float32))
        return self.kf.statePost[:4].flatten()

    def predict_future(self, lookahead_sec=0.1):
        state = self.kf.statePost.flatten()
        return [float(state[0] + state[4]*lookahead_sec), float(state[1] + state[5]*lookahead_sec), 
                float(state[2] + state[6]*lookahead_sec), float(state[3] + state[7]*lookahead_sec)]

kalman = ROIKalman()
smoothed_roi = None
last_box_seen = None
box_lock = threading.Lock()

bw_lock = threading.Lock()
total_bytes_sent = 0
last_bw_time = time.time()

def log_bandwidth(byte_count):
    if not BW_LOG: return
    global total_bytes_sent, last_bw_time
    with bw_lock:
        total_bytes_sent += byte_count
        now = time.time()
        if now - last_bw_time >= 1.0:
            mbps = (total_bytes_sent * 8) / (1024 * 1024)
            try:
                with open("log.txt", "a") as f:
                    f.write(f"[{time.strftime('%H:%M:%S')}] Alpha Mask Edge - Bandwidth: {mbps:.2f} Mbps\n")
            except: pass
            total_bytes_sent = 0
            last_bw_time = now

def capture_frames():
    global latest_frame
    if SAMPLE_VIDEO:
        print(f"Opening video file: {SAMPLE_VIDEO}")
        cap = cv2.VideoCapture(SAMPLE_VIDEO)
    else:
        print("Opening webcam...")
        cap = cv2.VideoCapture(0)
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
        cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'MJPG'))
        cap.set(cv2.CAP_PROP_FPS, 60)
    
    if not cap.isOpened():
        print(f"Error: Could not open source {SAMPLE_VIDEO if SAMPLE_VIDEO else 'webcam'}")
        return

    # Get original FPS for video files to avoid "fast-forward" playback
    source_fps = cap.get(cv2.CAP_PROP_FPS)
    if source_fps <= 0 or source_fps > 120: source_fps = 60 # Fallback
    frame_interval = 1.0 / source_fps
    print(f"Source FPS: {source_fps:.1f}")

    # Initialize FFmpeg process once
    ffmpeg_proc = start_ffmpeg_process()
    if ffmpeg_proc is None: return

    while True:
        start_time = time.time()
        success, frame = cap.read()
        if not success:
            if SAMPLE_VIDEO:
                cap.set(cv2.CAP_PROP_POS_FRAMES, 0) # Loop video
                continue
            break
        
        # 1. Update latest frame for low-res/preview path
        with lock:
            latest_frame = frame # No .copy() here to save CPU
            
        # 2. Push directly to high-res stream (Zero-Polling)
        process_and_push_frame(frame, ffmpeg_proc)

        # 3. Limit FPS for video files
        if SAMPLE_VIDEO:
            elapsed = time.time() - start_time
            time.sleep(max(0, frame_interval - elapsed))

def generate_low_res():
    while True:
        start_time = time.time()
        with lock:
            if latest_frame is None:
                continue
            low_res = cv2.resize(latest_frame, (300, 165))
            
            # Simulate precompression: JPEG encode/decode to introduce artifacts
            # This makes the preview and the AI feed match the actual transmission quality
            ret, tmp_buff = cv2.imencode('.jpg', low_res, [int(cv2.IMWRITE_JPEG_QUALITY), 30])
            if ret:
                low_res = cv2.imdecode(tmp_buff, cv2.IMREAD_COLOR)

            global latest_low_res
            latest_low_res = low_res.copy()
            
        ret, buffer = cv2.imencode('.jpg', low_res)
        frame_bytes = buffer.tobytes()
        yield (b'--frame\r\n'
               b'Content-Type: image/jpeg\r\n\r\n' + frame_bytes + b'\r\n')
        
        elapsed = time.time() - start_time
        time.sleep(max(0, (1/30.0) - elapsed)) # Increased to 30 FPS for responsiveness

@app.route('/video_feed')
def video_feed():
    return Response(generate_low_res(),
                    mimetype='multipart/x-mixed-replace; boundary=frame')

def inference_thread():
    global current_roi, smoothed_roi, last_box_seen
    last_inference_time = 0
    inference_interval = 0.02
    
    while True:
        loop_start = time.time()
        frame = None
        with lock:
            if latest_frame is not None:
                frame = latest_frame.copy()
                
        if frame is None:
            time.sleep(0.01)
            continue
            
        current_time = time.time()
        if current_time - last_inference_time > inference_interval:
            # Run YOLO on the full-res source frame
            results = model.track(frame, persist=True, tracker='bytetrack.yaml', stream=True, verbose=False, imgsz=544, conf=0.1)
            best_target = None
            
            for r in results:
                # PERSON / ANIMAL / ALL
                if len(r.boxes) > 0 and r.boxes.id is not None:
                    classes = r.boxes.cls.cpu().numpy()
                    target_mask = np.zeros_like(classes, dtype=bool)
                    if TRACK_MODE == "PERSON": target_mask = (classes == 0)
                    elif TRACK_MODE == "ANIMAL": target_mask = (classes >= 14) & (classes <= 23)
                    elif TRACK_MODE == "ALL": target_mask = (classes == 0) | ((classes >= 14) & (classes <= 23))
                        
                    if np.any(target_mask):
                        target_confs = r.boxes.conf.cpu().numpy()[target_mask]
                        target_boxes = r.boxes.xyxy.cpu().numpy()[target_mask]
                        target_classes = classes[target_mask]
                        idx = np.argmax(target_confs)
                        box = target_boxes[idx]
                        detected_cls = int(target_classes[idx])
                        label_name = "PERSON" if detected_cls == 0 else "ANIMAL"
                        cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
                        best_target = (np.array([cx, cy]), float(target_confs[idx]), label_name, box)
                        break
                        
            if best_target is not None:
                center, d_conf, label, dims_or_box = best_target
                cx, cy = center
                bx1, by1, bx2, by2 = dims_or_box
                
                with box_lock:
                    last_box_seen = [int(bx1), int(by1), int(bx2), int(by2), f"EDGE: {label} {d_conf:.2f}"]
                    
                tw, th = bx2 - bx1, by2 - by1
                target_w, target_h = tw * 1.3, th * 1.3
                target_w, target_h = max(128, min(800, target_w)), max(128, min(800, target_h))
                
                rx, ry = int(cx - target_w/2), int(cy - target_h/2)
                rw, rh = int(target_w), int(target_h)
                
                kalman.update([rx, ry, rw, rh])
                prx, pry, prw, prh = kalman.predict_future(lookahead_sec=0.10)
                
                if smoothed_roi is None: smoothed_roi = [int(prx), int(pry), int(prw), int(prh)]
                else:
                    smoothed_roi[0] = int(0.4 * prx + 0.6 * smoothed_roi[0])
                    smoothed_roi[1] = int(0.4 * pry + 0.6 * smoothed_roi[1])
                    smoothed_roi[2] = int(0.1 * prw + 0.9 * smoothed_roi[2])
                    smoothed_roi[3] = int(0.1 * prh + 0.9 * smoothed_roi[3])
                
                state = kalman.kf.statePost.flatten()
                v_pad_x = int(abs(state[4]) * 0.15)
                v_pad_y = int(abs(state[5]) * 0.15)
                smoothed_roi[2] = int(smoothed_roi[2] + v_pad_x)
                smoothed_roi[3] = int(smoothed_roi[3] + v_pad_y)
                
                with roi_lock:
                    current_roi = smoothed_roi.copy()
            
            last_inference_time = current_time
        time.sleep(max(0, 0.01 - (time.time() - loop_start)))

def start_ffmpeg_process():
    cmd = [
        'ffmpeg', '-y', '-f', 'image2pipe', '-framerate', '60', '-vcodec', 'mjpeg', '-i', '-',
        '-vcodec', 'copy', '-f', 'matroska', 'udp://127.0.0.1:5012?pkt_size=1316'
    ]
    try:
        print("Starting MJPEG-MKV high-res streamer...")
        return subprocess.Popen(cmd, stdin=subprocess.PIPE)
    except Exception as e:
        print(f"FFmpeg startup error: {e}")
        return None

def process_and_push_frame(frame, proc):
    global current_roi
    with roi_lock:
        rx, ry, rw, rh = current_roi
    
    # Quantize for stability (reduced step for better follow-cam responsiveness)
    q_rw = max(32, ((rw + 7) // 8) * 8)
    q_rh = max(32, ((rh + 7) // 8) * 8)
    
    # Use actual frame dimensions for robust clipping (video files might not be 1280x720)
    fh, fw = frame.shape[:2]
    x1 = max(0, min(rx - (q_rw - rw)//2, fw - q_rw))
    y1 = max(0, min(ry - (q_rh - rh)//2, fh - q_rh))
    
    # Fast feedback (reuse socket) - Include source dimensions [x, y, w, h, fw, fh]
    feedback_data = json.dumps([x1, y1, q_rw, q_rh, fw, fh]).encode()
    feedback_sock.sendto(feedback_data, ('127.0.0.1', 5013))
    
    # Create black frame
    masked_frame = np.zeros_like(frame)
    crop_h = min(q_rh, fh - y1)
    crop_w = min(q_rw, fw - x1)
    
    if crop_h > 0 and crop_w > 0:
        masked_frame[y1:y1+crop_h, x1:x1+crop_w] = frame[y1:y1+crop_h, x1:x1+crop_w]
        
        if DEBUG:
            with box_lock:
                if last_box_seen:
                    bx1, by1, bx2, by2, text = last_box_seen
                    # Draw box on the masked frame so the receiver gets it automatically
                    cv2.rectangle(masked_frame, (bx1, by1), (bx2, by2), (255, 0, 0), 3)
                    cv2.putText(masked_frame, text, (bx1, by1-10), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 0, 0), 2)
                    
        ret, jpeg_buffer = cv2.imencode('.jpg', masked_frame, [int(cv2.IMWRITE_JPEG_QUALITY), 95])
        if ret:
            frame_bytes = jpeg_buffer.tobytes()
            log_bandwidth(len(frame_bytes))
            try:
                proc.stdin.write(frame_bytes)
                proc.stdin.flush()
            except:
                pass

if __name__ == '__main__':
    threading.Thread(target=capture_frames, daemon=True).start()
    threading.Thread(target=inference_thread, daemon=True).start()
    
    threading.Thread(target=lambda: app.run(host='0.0.0.0', port=5000, threaded=True, use_reloader=False), daemon=True).start()

    print("Matroska Source running: Flask(5000), ROI(5001), Stream(5012)")

    while True:
        with lock:
            if latest_low_res is not None:
                # Resize the low-res (pixelated) frame to look like what the AI sees
                cv2.imshow("Source (Low Res Stream)", cv2.resize(latest_low_res, (1280, 720), interpolation=cv2.INTER_NEAREST))
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break
    cv2.destroyAllWindows()
