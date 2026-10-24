import cv2
import os

video_path = r"c:\Users\miste\Downloads\Telegram Desktop\video_2026-10-24_20-48-24.mp4"
cap = cv2.VideoCapture(video_path)
fps = cap.get(cv2.CAP_PROP_FPS)

# Let's sample frames between 3s and 25s every 0.2s
out_dir = r"c:\Projects\FastReader\tools\words_timeline"
os.makedirs(out_dir, exist_ok=True)

# Word area in 720x1280:
# Y is between ~380 and ~520, X is between ~150 and ~570
start_sec = 2.0
end_sec = 25.0

curr_sec = start_sec
frame_count = 0

while curr_sec <= end_sec:
    cap.set(cv2.CAP_PROP_POS_MSEC, curr_sec * 1000)
    ret, frame = cap.read()
    if not ret:
        break
    word_crop = frame[380:550, 150:570]
    cv2.imwrite(os.path.join(out_dir, f"w_{frame_count:03d}_{curr_sec:.2f}s.jpg"), word_crop)
    curr_sec += 0.25
    frame_count += 1

cap.release()
print(f"Saved {frame_count} word crops")
