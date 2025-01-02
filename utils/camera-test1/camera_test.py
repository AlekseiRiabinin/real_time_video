import cv2


def testDevice(source):
    cap = cv2.VideoCapture(source)
    if cap is None or not cap.isOpened():
        print('Warning: unable to open video source: ', source)
    else:
        print('Video source: ', source)


testDevice(0)
testDevice(1)
testDevice(2)
testDevice(3)