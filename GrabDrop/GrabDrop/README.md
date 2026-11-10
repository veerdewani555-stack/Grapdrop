# Grab & Drop (Android → Windows)

## Android
1. Download the hand model into app/src/main/assets/ (exact name):
   https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
2. Open this folder in Android Studio, let Gradle sync, plug in the phone (USB debugging), press Run.
3. Tap "Start Grab & Drop", allow Camera / Notifications / Photos.

## Windows
pip install zeroconf mediapipe opencv-python requests
python windows/receiver.py

Phone and laptop must be on the same Wi-Fi (not a guest network).
