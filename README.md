# MobiSpectral Android
This application is written in Kotlin and shows working of our work [MobiSpectral](https://github.com/) on its intended device, Smartphones.

## Hardware Requirements
The project is based on the ability of recent smartphones to capture images beyond the visible range in the EM spectrum. Recent smartphones come with specialized cameras which are used for robust face authentication systems and these phone cameras capture images in the Near Infrared range. We use the image captured from this special camera as well as a regular RGB image to figure out if the fruit in the pictures is organic or non-organic.

## Pipeline
1. Image Capturing: RGB followed by NIR.
3. Image Alignment: Aligning the two images captured.
4. Deep White Balancing: Android ported models from [[Deep White Balance](https://github.com/mahmoudnafifi/Deep_White_Balance), [Models](https://github.com/mahmoudnafifi/Deep_White_Balance/tree/master/PyTorch/models)].
5. Patch Selection: Selecting the part of image we want to use.
6. Hyperspectral Reconstruction: RGB+NIR -> Hypercube.
7. Classification: based on 1-D signatures selection.

## Working Modes
- Simple Mode: Simple Mode is for users who want to tell what class the produce belong to. After aligning the two images, the app will pre-select a bounding box which is to be used in the reconstruction phase. Although, the user can tap on the images to change the location of the bounding box, to a zone where the fruit actually is in the images. After Reconstructing the hypercube, it will then divide the bounding box into zones of 16*16 pixels and create a signature which is representative of that zone, and classify the averaged signature in non-organic and organic classes.
- Advanced Mode: Advanced Mode allows the user to see more in depth analysis of the hypercube. After Normalizing and Aligning the images, it allows the user to reconstruct the whole hypercube or a part, 128*128, of it (you can select it by tapping on it). After getting the hypercube, you can tap on individual pixels and see what class each pixel (spectral signature) belongs to. On tapping the "Analysis" button the app shows how the spectral signature looks like and shows the predicted output.

## Download Test Set
If your phone does not meet the hardware requirement (NIR camera), the application has an "offline mode" which allows the user to run the provided images through the model to see the functionality and feasibility of such an application. The test set of organic and non-organic fruits consisting of apples, kiwis, blueberries can be found on this [link](https://drive.google.com/file/d/1n3a9339pDgV6Gq013Jl90_L0w76Xu3pp/view?usp=drive_link "Kiwi Test Dataset"). Unzip the images from the link to your phone and select a pair of images, choose RGB first and NIR second (Although the app can correct the order if there is "RGB" or "NIR" sub-string in the filenames).

## Download the application
The application can either be built using Android Studio (or Eclipse) or the pre-built apk can be downloaded from this [link](https://drive.google.com/file/d/1wo8ZUS3-xoAcLv0q3D-vBJ4dtZhvELC_/view?usp=drive_link "MobiSpectral Android Application").

If the application could not be downloaded, disable Play Protect (service from Google that prevents users from installing applications which are not from Play Store) from your phone for the time being. Google Play Store > Click your profile picture (top right) > Play Protect > Settings Cog (top right) > Disable the option "Scan apps with Play Protect".

## Technical Specifications
- Kotlin 1.8.20 (Also tested on 1.7.21)
- Gradle: 8.0.2 [Amazon Corretto Version] (Also tested on 7.5.0, 7.4.0, 4.2.2)
- Java Version: 18 (Also tested on 17, 16)
- SDK versions 33 (target), 23 (minimum)
- AndroidX
- Dependencies
	- PyTorch: 1.8.0 (versions above it contain some bugs and uses Lite Interpreter which did not convert models to PyTorch mobile version)
	- OpenCV: 3.4.3

## References
- Picture capturing code using Camera API 2 and was forked from [Android/Camera2Basic](https://github.com/android/camera-samples/tree/main/Camera2Basic) and built upon from there.
- Models from [Deep White Balancing](https://github.com/mahmoudnafifi/Deep_White_Balance) were ported to PyTorch Android.