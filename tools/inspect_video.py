import cv2
import os

video_path = r"c:\Users\miste\Downloads\Telegram Desktop\video_2026-10-24_20-48-24.mp4"
cap = cv2.VideoCapture(video_path)

if not cap.isOpened():
    print(f"Error: Could not open video at {video_path}")
    exit(1)

fps = cap.get(cv2.CAP_PROP_FPS)
total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
duration = total_frames / fps if fps > 0 else 0
width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

print(f"Video Info:")
print(f"  Resolution: {width}x{height}")
print(f"  FPS: {fps:.2f}")
print(f"  Total frames: {total_frames}")
print(f"  Duration: {duration:.2f} seconds")

# Extract 1 frame every 0.5s or 1s to inspect
out_dir = r"c:\Projects\FastReader\tools\video_frames"
os.makedirs(out_dir, exist_ok=True)

# Let's save frames every 5-10 frames around seconds 2 to 10
frame_idx = 0
saved_count = 0
while cap.isOpened() and saved_count < 25:
    ret, frame = cap.read()
    if not ret:
        break
    # Save every ~10th frame (about every 0.3-0.5s)
    if frame_idx % max(1, int(fps * 0.4)) == 0:
        frame_name = f"frame_{saved_count:03d}_sec_{frame_idx/fps:.2f}.jpg"
        out_path = os.path.join(out_dir, frame_name)
        # Resize to reasonable size for inspection if needed
        cv2.imwrite(out_path, frame)
        print(f"Saved {frame_name}")
        saved_count += 1
    frame_idx += 1

cap.release()
print(f"Saved {saved_count} frames to {out_dir}")
