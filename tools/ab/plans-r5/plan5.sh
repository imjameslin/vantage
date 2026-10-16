#!/bin/zsh
# Step 4: parity (pinned world, fresh copy per run); ALL = every candidate toggle on (A, C, D, E pregrow + async textures)
SC=tools/ab/scenes.sh
zsh $SC r5ab-all-1 scenes --jvm=-Dmcmetal.lateThrottle=true --jvm=-Dmcmetal.renameSmallWrites=true --jvm=-Dmcmetal.transientHysteresis=true --jvm=-Dmc.fastAnimateTick=true --jvm=-Dmc.fastTextureAnimation=true --jvm=-Dmc.terrain.pregrow=true --jvm=-Dmc.asyncEntityTextures=true
zsh $SC r5ab-off-1 scenes
zsh $SC r5ab-all-2 scenes --jvm=-Dmcmetal.lateThrottle=true --jvm=-Dmcmetal.renameSmallWrites=true --jvm=-Dmcmetal.transientHysteresis=true --jvm=-Dmc.fastAnimateTick=true --jvm=-Dmc.fastTextureAnimation=true --jvm=-Dmc.terrain.pregrow=true --jvm=-Dmc.asyncEntityTextures=true
zsh $SC r5ab-all-noed scenes --jvm=-Dmcmetal.lateThrottle=true --jvm=-Dmcmetal.renameSmallWrites=true --jvm=-Dmcmetal.transientHysteresis=true --jvm=-Dmc.fastAnimateTick=true --jvm=-Dmc.fastTextureAnimation=true --jvm=-Dmc.terrain.pregrow=true --jvm=-Dmc.asyncEntityTextures=true --jvm=-Dmc.zoomEntityDistance=false --bench-prop only=spyglass_entities,spyglass_far_entities,zoom_entities,zoom_far_entities
zsh $SC r5ab-all-verify scenes --jvm=-Dmcmetal.lateThrottle=true --jvm=-Dmcmetal.renameSmallWrites=true --jvm=-Dmcmetal.transientHysteresis=true --jvm=-Dmc.fastAnimateTick=true --jvm=-Dmc.fastTextureAnimation=true --jvm=-Dmc.terrain.pregrow=true --jvm=-Dmc.asyncEntityTextures=true --jvm=-Dmc.fastAnimateTick.verify=true
zsh $SC r5ab-zq-all zoomseq --jvm=-Dmcmetal.lateThrottle=true --jvm=-Dmcmetal.renameSmallWrites=true --jvm=-Dmcmetal.transientHysteresis=true --jvm=-Dmc.fastAnimateTick=true --jvm=-Dmc.fastTextureAnimation=true --jvm=-Dmc.terrain.pregrow=true --jvm=-Dmc.asyncEntityTextures=true
zsh $SC r5ab-zq-off zoomseq
