# The SongateOS layer on top of the LineageOS 21 TrebleDroid GSI. See work/SONGATEOS_IMAGE_PLAN.md.
SONGATEOS_VERSION := 2.2.1
LOCAL_SONGATEOS := vendor/songateos

PRODUCT_SYSTEM_PROPERTIES += \
    ro.songateos.version=$(SONGATEOS_VERSION) \
    ro.songateos.hold_framework=1

# Display size (user decision 2026-11-18): 288 dpi is the R4's default, Android's "Small" step on HiBy's 320
# (vendor ro.sf.lcd_density). Product properties load after vendor's, so this one wins; Settings' Display size then
# shows 288 as Default and scales its steps from it.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.sf.lcd_density=288

# No notification or alarm sound by default: the R4 has no speaker, and sounds glitch the music output
PRODUCT_PRODUCT_PROPERTIES += \
    ro.config.notification_sound= \
    ro.config.alarm_alert=

# Songate: the only launcher (presigned with its release key); replaces Trebuchet and LineageOS's setup wizard
PRODUCT_PACKAGES += \
    Songate
PRODUCT_COPY_FILES += \
    $(LOCAL_SONGATEOS)/songate_config/songateos-default-permissions.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/default-permissions/songateos-default-permissions.xml \
    $(LOCAL_SONGATEOS)/songate_config/songateos-sysconfig.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/sysconfig/songateos-sysconfig.xml

# The HiBy R4 service and the HiBy pages in Settings (compiled into Settings, see patches/apply_settings.py)
PRODUCT_PACKAGES += \
    HiByR4Service

# The former Magisk module: root scripts + data files (init: songateos.rc) and the master volume helper
PRODUCT_PACKAGES += \
    songateos_post-fs-data.sh \
    songateos_service.sh \
    songateos_hiby_settings.sh \
    songateos_ota_bridge.sh \
    songateos_hold_switch.sh \
    songateos_audio_policy_configuration.xml \
    songateos_gpio-keys-nowake.kl \
    songateos_bootanimation_hold.zip \
    songateos_mastervol
PRODUCT_COPY_FILES += \
    $(LOCAL_SONGATEOS)/zz-hibyr4-no-telephony.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/zz-hibyr4-no-telephony.xml

SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(LOCAL_SONGATEOS)/sepolicy

# Look and defaults
PRODUCT_PACKAGES += \
    HiByR4NoVoice \
    HiByR4FallbackHome \
    HiByR4BlackWallpaper \
    HiByR4FontInter \
    HiByR4StyleAndroid \
    HiByR4StyleSystemUI \
    SongateOSDefaultsSettings \
    SongateOSDefaultsAndroid

# Boot animation (Songate's opening): LineageOS's bootanimation.zip module installs this file instead of its own
TARGET_BOOTANIMATION := $(LOCAL_SONGATEOS)/branding/bootanimation.zip

# Inter (Songate's font, SIL OFL 1.1)
PRODUCT_COPY_FILES += \
    $(LOCAL_SONGATEOS)/fonts/Inter-Regular.ttf:$(TARGET_COPY_OUT_PRODUCT)/fonts/Inter-Regular.ttf \
    $(LOCAL_SONGATEOS)/fonts/Inter-SemiBold.ttf:$(TARGET_COPY_OUT_PRODUCT)/fonts/Inter-SemiBold.ttf \
    $(LOCAL_SONGATEOS)/fonts/Inter-Bold.ttf:$(TARGET_COPY_OUT_PRODUCT)/fonts/Inter-Bold.ttf \
    $(LOCAL_SONGATEOS)/fonts/Inter-ExtraBold.ttf:$(TARGET_COPY_OUT_PRODUCT)/fonts/Inter-ExtraBold.ttf \
    $(LOCAL_SONGATEOS)/fonts/OFL-Inter.txt:$(TARGET_COPY_OUT_PRODUCT)/fonts/OFL-Inter.txt

# Off-mode charging animation (Android's charger reads /product/etc/res when present). The animation.txt goes in
# through LineageOS's lineage_charger_animation module (patches/apply_charger.py); only the images are copied here.
PRODUCT_COPY_FILES += \
    $(LOCAL_SONGATEOS)/branding/charger/res/images/charger/songate_scale.png:$(TARGET_COPY_OUT_PRODUCT)/etc/res/images/charger/songate_scale.png \
    $(LOCAL_SONGATEOS)/branding/charger/res/images/charger/songate_font.png:$(TARGET_COPY_OUT_PRODUCT)/etc/res/images/charger/songate_font.png \
    $(LOCAL_SONGATEOS)/branding/charger/res/images/charger/songate_fail.png:$(TARGET_COPY_OUT_PRODUCT)/etc/res/images/charger/songate_fail.png
