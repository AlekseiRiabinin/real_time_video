import cv2
import os

# Path to the directory containing images
image_folder = '/home/aleksei/Projects/data/msl-images/calibrated/'
video_name = '/home/aleksei/Projects/data/video.mp4'

images = [img for img in os.listdir(image_folder) if img.endswith(".JPG")]
images.sort()  # Ensure the images are in the correct order

frame = cv2.imread(os.path.join(image_folder, images[0]))
height, width, layers = frame.shape

# Use the 'mp4v' codec for mp4 files
video = cv2.VideoWriter(video_name, cv2.VideoWriter_fourcc(*'mp4v'), 1, (width, height))

for image in images:
    video.write(cv2.imread(os.path.join(image_folder, image)))

cv2.destroyAllWindows()
video.release()
