#!/bin/zsh
# Finalize parity on the frozen client-r5.jar (shipped defaults), fresh copy of run/scenes-pinned-r4 per run.
export SCENES_JAR=build/client-r5.jar SCENES_BJAR=build/bench-r5.jar
SC=tools/ab/scenes.sh
OFF=(--jvm=-Dmcmetal.lateThrottle=false --jvm=-Dmcmetal.renameSmallWrites=false --jvm=-Dmc.fastAnimateTick=false
     --jvm=-Dmc.fastTextureAnimation=false --jvm=-Dmc.terrain.pregrow=false)
zsh $SC r5f-1 scenes
zsh $SC r5f-off scenes $OFF                        # same jar, every round-5 toggle off: noise floor without the toggles
zsh $SC r5f-2 scenes
zsh $SC r5f-noed scenes --jvm=-Dmc.zoomEntityDistance=false
zsh $SC r5f-verify scenes --jvm=-Dmc.fastAnimateTick.verify=true
zsh $SC r5f-zq zoomseq
zsh $SC r5f-zq-off zoomseq $OFF
