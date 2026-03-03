import subprocess
import time
import sys
import os

def run_manager():
    print("--- Vision Stream POC (Matroska Sandbox) ---")
    
    # 1. Start Source
    print("[1/2] Starting Source (source_mkv.py)...")
    source_proc = subprocess.Popen([sys.executable, "source_mkv.py"])
    
    print("Waiting 5s for server initialization...")
    time.sleep(5)
    
    # 2. Start Receiver
    print("[2/2] Starting Receiver (receiver_mkv.py)...")
    receiver_proc = subprocess.Popen([sys.executable, "receiver_mkv.py"])
    
    try:
        while True:
            if source_proc.poll() is not None:
                print("Source process terminated.")
                break
            if receiver_proc.poll() is not None:
                print("Receiver process terminated.")
                break
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nShutdown requested...")
    finally:
        print("Cleaning up processes...")
        source_proc.terminate()
        receiver_proc.terminate()
        print("Done.")

if __name__ == "__main__":
    run_manager()
