import csv
import os


def convert_txt_to_csv(txt_files, output_csv, image_base_path):
    with open(output_csv, 'w', newline='') as csvfile:
        csvwriter = csv.writer(csvfile)
        csvwriter.writerow(['image_path', 'label'])  # Write header

        for txt_file in txt_files:
            with open(txt_file, 'r') as file:
                for line in file:
                    image_path, label = line.strip().split()
                    full_image_path = os.path.join(image_base_path, image_path)
                    csvwriter.writerow([full_image_path, label])

# List of .txt files to convert
txt_files = [
    '/home/aleksei/Projects/real_time_video/apps/spark-ml/train-calibrated-shuffled.txt',
    '/home/aleksei/Projects/real_time_video/apps/spark-ml/val-calibrated-shuffled.txt',
    '/home/aleksei/Projects/real_time_video/apps/spark-ml/test-calibrated-shuffled.txt'
]

# Output CSV file
output_csv = '/home/aleksei/Projects/real_time_video/apps/spark-ml/data/mars_images.csv'

# Base path to the images
image_base_path = '/home/aleksei/Projects/real_time_video/apps/spark-ml/data/'

# Convert .txt files to CSV
convert_txt_to_csv(txt_files, output_csv, image_base_path)
