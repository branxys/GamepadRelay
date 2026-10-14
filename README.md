<div align="center">

# 🎮 GamepadRelay

### Your phone's Bluetooth controllers, on your PC. Over Wi-Fi.

Connect a controller to your Android phone and play PC games with it as a virtual Xbox 360 controller. Works with the screen off on rooted phones.

[![Latest release](https://img.shields.io/github/v/release/branxys/GamepadRelay?style=for-the-badge&label=download&color=2ea44f)](https://github.com/branxys/GamepadRelay/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/branxys/GamepadRelay/total?style=for-the-badge&color=blue)](https://github.com/branxys/GamepadRelay/releases)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Windows](https://img.shields.io/badge/Windows-10%2F11-0078D6?style=for-the-badge&logo=windows&logoColor=white)

</div>


----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
## How to Use

*REQUIREMENTS*: [ViGEmBus driver](https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_1.22.0_x64_x86_arm64.exe)

1: Download the executable on your pc and the apk on your phone from  [the latest release](https://github.com/branxys/GamepadRelay/releases/latest) 

2: Open the .exe on your pc and it will start listening immediately

3: Install the .apk on your phone

4: Connect the Bluetooth gamepad to your phone

5: Open the app on your phone and click "find pc"

6: Click on Start relay and everything should work fine.

If you have root, you can use the app while the screen is turned off.

 ## COMMON ISSUES

If the app on android shows "no pc found", run this command in command prompt (administrator)


``netsh advfirewall firewall add rule name="PadRelay" dir=in action=allow protocol=UDP localport=9876 profile=any``
