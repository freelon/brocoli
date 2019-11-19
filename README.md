# BRoCoLi
A *Better Robust Communication Library* for Android ad hoc networking

In the course of the the BMBF-funded project [RESIBES](https://www.resibes.de) we at the Paderborn University developed an android library that allows for message dissemination without an active internet connection. 
We create a network where every participating device gets assigned a unique ID. After that any content can be either sent directly to a device with a specific ID or broadcasted to any device. To achieve this without an internet connection we connect nearby devices and route data over multiple hops from one device to the next, until the destination is reached.
The network quality here highly depends on the density of devices participating in a network. The more people are participating - and the more they are moving around - the faster information can spread. 
In this process data are stored on intermediate devices and can be moved around like this - a store, carry, forward dissemination scheme. This is often found in [Delay Tolerant Networks](https://en.wikipedia.org/wiki/Delay-tolerant_networking).

While traditional ad hoc networks would use special radio features for that (e.g. the WiFi Ad Hoc mode), Android prohibits the use of those and thus forced us to use another approach. Building on top of the Google Play Nearby library, we create pairwise connections between nearby devices instead. This allows the BRoCoLi library to be used within apps on off-the-shelf smartphones without any prerequisites other than the Google Play app being installed - rooting the phones or other modifications are not necessary.

This repository contains a clean and easy to use library without special algorithms implemented.
It was tested in multiple field experiments on different devices, among which are different Android Smartphones as well as Raspberry Pis running Android Things.

## Usage
For now you have to download the repository, which contains an Android Studio project containing two modules: the library itself and and example app. From that app you can see how to use the library to create networks and send messages. More documentation is right now only available in German on request.

## Contact
For questions about the functionality or usage please create an issue.
