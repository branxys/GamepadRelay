          How to Use

Download the executable on your pc and the apk on your phone from [here](https://github.com/branxys/GamepadRelay/releases/latest) 

Open the .exe on your pc and it will start listening immediately

Install the .apk on your phone

Connect the bluetooth gamepad to your phone

Open the app on your phone, click find pc and everything should run fine

If you have root, you can use the app while the screen is turned off.

          COMMON ERRORS

If the app on android shows "no pc found", run this command in command prompt (administrator)


``netsh advfirewall firewall add rule name="PadRelay" dir=in action=allow protocol=UDP localport=9876 profile=any``
