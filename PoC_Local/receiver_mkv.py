import cv2
import time
import threading
import numpy as np

# Configuration
LOW_RES_URL = "http://localhost:5000/video_feed"
HIGH_RES_UDP = "udp://127.0.0.1:5012"

DISP_WIDTH = 1280
DISP_HEIGHT = 720

latest_low_res = None
latest_high_res = None
low_res_lock = threading.Lock()
high_res_lock = threading.Lock()

def low_res_reader():
    global latest_low_res
    cap = cv2.VideoCapture(LOW_RES_URL)
    while True:
        ret, frame = cap.read()
        if ret:
            with low_res_lock:
                latest_low_res = cv2.resize(frame, (DISP_WIDTH, DISP_HEIGHT))
        else:
            time.sleep(0.1)

def high_res_reader():
    global latest_high_res
    # MKV reader: might need probesize or re-init on change
    # Remove fifo_size=0 for MKV, let FFmpeg handle the buffer
    url = f"{HIGH_RES_UDP}"
    print(f"High-res reader connecting to {url}...")
    cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
    
    count = 0
    while True:
        ret, frame = cap.read()
        if ret:
            if count % 50 == 0:
                print(f"High-res frame: {frame.shape[1]}x{frame.shape[0]}")
            with high_res_lock:
                latest_high_res = frame.copy()
            count += 1
        else:
            time.sleep(0.1)
            if not cap.isOpened():
                print("High-res stream closed, reconnecting...")
                cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)

def main():
    threading.Thread(target=low_res_reader, daemon=True).start()
    threading.Thread(target=high_res_reader, daemon=True).start()
    
    cv2.namedWindow("Target (High-Res Enhanced AI)", cv2.WINDOW_NORMAL)
    cv2.resizeWindow("Target (High-Res Enhanced AI)", DISP_WIDTH, DISP_HEIGHT)

    last_inference_time = 0
    inference_interval = 0.02

    print("MKV Receiver (Composite Context Inference) active.")

    while True:
        loop_start = time.time()
        
        base_frame = None
        with low_res_lock:
            if latest_low_res is not None:
                base_frame = latest_low_res.copy()
        if base_frame is None:
            time.sleep(0.01)
            continue

        # Create Combined Frame (High-Res pasted onto Low-Res)
        combined_frame = base_frame.copy()
        
        hs_img = None
        with high_res_lock:
            if latest_high_res is not None:
                hs_img = latest_high_res.copy()
                
        if hs_img is not None and hs_img.shape[0] >= 32 and hs_img.shape[1] >= 32:
            # Create a mask of the non-black regions directly from the raw incoming frame
            gray = cv2.cvtColor(hs_img, cv2.COLOR_BGR2GRAY)
            _, mask = cv2.threshold(gray, 20, 255, cv2.THRESH_BINARY)
            
            # Find the strict bounding box
            coords = cv2.findNonZero(mask)
            if coords is not None:
                x, y, w, h = cv2.boundingRect(coords)
                pad = 4  # Shrink slightly to eliminate compression artifacts
                x1, y1 = max(0, x + pad), max(0, y + pad)
                x2, y2 = min(hs_img.shape[1], x + w - pad), min(hs_img.shape[0], y + h - pad)
                
                if x2 > x1 and y2 > y1:
                    # Crop the actual un-interpolated ROI
                    roi_crop = hs_img[y1:y2, x1:x2]
                    
                    # Map the coordinates onto the display space
                    scale_x = DISP_WIDTH / hs_img.shape[1]
                    scale_y = DISP_HEIGHT / hs_img.shape[0]
                    dx1, dy1 = int(x1 * scale_x), int(y1 * scale_y)
                    dx2, dy2 = int(x2 * scale_x), int(y2 * scale_y)
                    
                    target_w = dx2 - dx1
                    target_h = dy2 - dy1
                    if target_w > 0 and target_h > 0:
                        # Resize only the crop (preventing any black borders from scaling)
                        roi_resized = cv2.resize(roi_crop, (target_w, target_h))
                        # Paste into the designated region on the low-res context stream
                        combined_frame[dy1:dy2, dx1:dx2] = roi_resized

        cv2.imshow("Target (High-Res Enhanced AI)", combined_frame)
        
        if cv2.waitKey(1) & 0xFF == ord('q'): break
        time.sleep(max(0, 0.01 - (time.time() - loop_start)))


    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
