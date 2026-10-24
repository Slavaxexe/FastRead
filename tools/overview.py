import cv2
import os

video_path = r"c:\Users\miste\Downloads\Telegram Desktop\video_2026-10-24_20-48-24.mp4"
cap = cv2.VideoCapture(video_path)
fps = cap.get(cv2.CAP_PROP_FPS)
total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
duration = total_frames / fps

out_dir = r"c:\Projects\FastReader\tools\overview_timeline"
os.makedirs(out_dir, exist_ok=True)

# Sample every 5 seconds
for s in range(0, int(duration), 5):
    cap.set(cv2.CAP_PROP_POS_MSEC, s * 1000)
    ret, frame = cap.read()
    if ret:
        cv2.imwrite(os.path.join(out_dir, f"sec_{s:03d}.jpg"), frame)

cap.release()
print("Saved overview frames")
