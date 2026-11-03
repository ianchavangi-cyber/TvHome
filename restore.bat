@echo off
REM Usage: restore.bat <TV_IP>
REM Puts the TV back: re-enables the 6 packages disabled earlier, restores stock animation scales,
REM and re-enables the stock launcher (if we ever disabled it).
set A=adb -s %1:5555 shell
adb connect %1:5555
%A% pm enable --user 0 com.google.android.tvlauncher
%A% pm enable --user 0 tv.anoki.acr.controller
%A% pm enable --user 0 com.cltv.fast
%A% pm enable --user 0 fusion.android.tv.demo
%A% pm enable --user 0 com.google.android.partnersetup
%A% pm enable --user 0 com.google.android.play.games
%A% pm enable --user 0 com.google.android.marvin.talkback
%A% settings put global window_animation_scale 1.0
%A% settings put global transition_animation_scale 1.0
%A% settings put global animator_duration_scale 1.0
%A% pm uninstall --user 0 com.tvhome.launcher
echo Done. Reboot the TV: adb reboot
