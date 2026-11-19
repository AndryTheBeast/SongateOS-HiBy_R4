# SongateOS for the HiBy R4 (standard model): the LineageOS 21 TrebleDroid GSI (lineage_arm64_bvN, same contents as
# device/phh/treble/lineage_arm64_bvN.mk, which stays untouched) plus the SongateOS layer (songateos.mk).
TARGET_GAPPS_ARCH := arm64
include build/make/target/product/aosp_arm64.mk
$(call inherit-product, device/phh/treble/base.mk)
$(call inherit-product, device/phh/treble/lineage.mk)
$(call inherit-product, vendor/songateos/songateos.mk)

PRODUCT_NAME := songateos_r4
PRODUCT_DEVICE := tdgsi_arm64_ab
PRODUCT_BRAND := SongateOS
PRODUCT_SYSTEM_BRAND := SongateOS
PRODUCT_MANUFACTURER := HiBy
PRODUCT_MODEL := HiBy R4
PRODUCT_CHARACTERISTICS := device

# British English only (British spelling, day/month dates, 24-hour clock by default)
PRODUCT_LOCALES := en_GB
