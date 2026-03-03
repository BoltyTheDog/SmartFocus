# SmartFocus Repository

This repository contains projects and proof-of-concept code for SmartFocus, a system utilizing YOLO object detection and WebRTC-based communication for real-time video processing. The workspace is organized into the following key components:

## Directory Overview

- **apps_kotlin/**
  - Contains Android applications built with Kotlin.
  - **SurveillanceDrone_app**: Android app integrating WebRTC and YOLO for drone video feed processing and encoding.
  - **SurveillanceModel_app**: Android app integrating WebRTC and YOLO for camera video feed processing and encoding.

- **PoC_Local/**
  - Proof-of-concept scripts demonstrating YOLO inference locally.
  - Includes `plot_bandwidth.py`, `receiver_mkv.py`, `run_mkv.py`, `source_mkv.py`.
  - Supports models such as `yolo11n.pt`, `yolo11n.torchscript`, and `yolov8n.pt`.

## Model Variants

- **YOLO Proof-of-Concept**: Baseline detection model used in early experiments.
- **Optimized Mobile Models**: Versions tuned for mobile devices and DJI drones.
  - Normal mobile model.
  - DJI drone–specific optimizations.

## Communication Protocol

The system is built around a **custom WebRTC protocol** featuring:

- **Signaling:** simple HTTP POST offers to an Android‑hosted server (`8888`), which returns a munged SDP answer. The client must be on the same local network.
- **Dual video tracks:**
  1. `context_track` – low‑resolution overview (e.g. 240×136)
  2. `source_track` – high‑resolution patches or full frames (up to 1920×1080)
  The receiver blends them using alpha masks for smooth transitions.
- **JSON command channel:** a reliable DataChannel named `commands` carries control messages (mode changes, class allow‑lists, hybrid ROI, etc.) in JSON format. Commands are sent from the Python client; the Android app listens and updates its streaming logic.
- **Bitrate & quality tweaks:** the Android server modifies SDP and sets RTCP encoding parameters to force higher bitrates (1‑8 Mbps) and maintain resolution.

This lightweight protocol lets any compatible client (Python, desktop, another phone) stream and control video using the same conventions.

---

## Hackathon

This project was co‑developed during the **Open Gateway Talent Arena Hackathon 2026**.

**Team members:**
- Vladislav Lapin
- Jan Viñas
- Erhan Kesken _(good inconditional support)_
- David Garcia Cirauqui

*(no unnecessary NaC code included)*
