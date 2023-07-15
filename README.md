# MobiSpectral Android
This application is written in Kotlin and shows working of our work [MobiSpectral](https://github.com/) on its intended device, Smartphone.

## Hardware Requirements
The project is based on the ability of recent smartphones to capture images beyond the visible range in the EM spectrum. Recent smartphones come with specialized cameras which are used for robust face authentication systems and these phone cameras capture images in the Near Infrared range. We use the image captured from this special camera as well as a regular RGB image to figure out if the fruit in the pictures is organic or non-organic.

## Features
- Simple Mode:
- Advanced Mode:

## Pipeline
1. Image Capturing: RGB followed by NIR.
3. Image Alignment: Aligning the two images captured.
4. Deep White Balancing: Android ported models from [[Deep White Balance](https://github.com/mahmoudnafifi/Deep_White_Balance), [Models](https://github.com/mahmoudnafifi/Deep_White_Balance/tree/master/PyTorch/models)].
5. Patch Selection: Selecting the part of image we want to use.
6. Hyperspectral Reconstruction: RGB+NIR -> Hypercube.
7. Classification: based on 1-D signatures selection.

## Download Test Set
If your phone does not meet the hardware requirement (NIR camera), the application has an "offline mode" which allows the user to run the provided images through the model to see the functionality and feasibility of such an application. The test set of organic and non-organic fruits consisting of apples, kiwis, blueberries can be found on this [link](). Unzip the images from the link to your phone

## Download the application
The application can either be built using Android Studio (or Eclipse) or the pre-built apk can be downloaded from this [link]().

## Getting Started
Fork the repository or download the package from the side pane to get started.

## References
- Picture capturing code using Camera API 2 and was forked from [Android/Camera2Basic](https://github.com/android/camera-samples/tree/main/Camera2Basic) and built upon from there.
- Models from [Deep White Balancing](https://github.com/mahmoudnafifi/Deep_White_Balance) were ported to PyTorch Android.