----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
## How to Use

*REQUIREMENTS*: [ViGEmBus driver](https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_1.22.0_x64_x86_arm64.exe)

1: Download the executable on your pc and the apk on your phone from [here](https://github.com/branxys/GamepadRelay/releases/latest) 

2: Open the .exe on your pc and it will start listening immediately

3: Install the .apk on your phone

4: Connect the bluetooth gamepad to your phone

5: Open the app on your phone, click find pc and everything should run fine

If you have root, you can use the app while the screen is turned off.

 ## COMMON ERRORS

If the app on android shows "no pc found", run this command in command prompt (administrator)


``netsh advfirewall firewall add rule name="PadRelay" dir=in action=allow protocol=UDP localport=9876 profile=any``
