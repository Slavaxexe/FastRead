import cv2
import os

video_path = r"c:\Users\miste\Downloads\Telegram Desktop\video_2026-10-24_20-48-24.mp4"
cap = cv2.VideoCapture(video_path)

out_dir = r"c:\Projects\FastReader\tools\video_frames_playback"
os.makedirs(out_dir, exist_ok=True)

fps = cap.get(cv2.CAP_PROP_FPS)

# Sample frames between second 2 and second 15 at ~5 fps (every 5-6 frames)
start_sec = 1.5
end_sec = 12.0
start_frame = int(start_sec * fps)
end_frame = int(end_sec * fps)

cap.set(cv2.CAP_PROP_POS_FRAMES, start_frame)
curr_frame = start_frame
saved = 0

step = int(fps / 5) # ~5 frames per second

while curr_frame <= end_frame:
    ret, frame = cap.read()
    if not ret:
        break
    if (curr_frame - start_frame) % step == 0:
        cv2.imwrite(os.path.join(out_dir, f"play_{saved:03d}_{curr_frame/fps:.2f}s.jpg"), frame)
        saved += 1
    curr_frame += 1

cap.release()
print(f"Saved {saved} playback frames")
