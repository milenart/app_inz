# Indoor Positioning Data Logger (app_inz)

A simple Android application for collecting sensor data useful in indoor positioning systems.  
The app allows logging measurements from different smartphone sensors to support research and development in positioning algorithms.

---

## Features

- Wi-Fi RSSI scanning – capture received signal strength from nearby Wi-Fi networks  
- Bluetooth Low Energy (BLE) scanning – collect RSSI values from nearby beacons and devices  
- Inertial sensors:
  - Accelerometer  
  - Gyroscope  
  - Barometer (pressure sensor)  
- CSV import – load path data stored in **2180 coordinate system** (x, y, floor) to create realistic walking trajectories inside Warsaw University of Technology buildings  

---

## Use Cases

- Research and experiments with indoor positioning techniques  
- Data collection for machine learning models (fingerprinting, sensor fusion, etc.)  
- Academic projects related to geoinformatics and navigation systems  
- Simulation of realistic movement paths within the Main Building of Warsaw University of Technology  

---

## Technologies

- Kotlin – core Android development  
- Android SDK – access to device sensors and system services  

