#!/bin/zsh
SC=tools/ab/scenes.sh
zsh $SC r5ab-zq-A zoomseq --jvm=-Dmcmetal.lateThrottle=true
zsh $SC r5ab-zq-D zoomseq --jvm=-Dmc.fastAnimateTick=true --jvm=-Dmc.fastTextureAnimation=true
zsh $SC r5ab-zq-off2 zoomseq
zsh $SC r5ab-zq-all2 zoomseq --jvm=-Dmcmetal.lateThrottle=true --jvm=-Dmcmetal.renameSmallWrites=true --jvm=-Dmcmetal.transientHysteresis=true --jvm=-Dmc.fastAnimateTick=true --jvm=-Dmc.fastTextureAnimation=true --jvm=-Dmc.terrain.pregrow=true --jvm=-Dmc.asyncEntityTextures=true
