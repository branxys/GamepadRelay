Common Errors


If the app on android shows "no pc found", run this command in command prompt (administrator)


``netsh advfirewall firewall add rule name="PadRelay" dir=in action=allow protocol=UDP localport=9876 profile=any``
