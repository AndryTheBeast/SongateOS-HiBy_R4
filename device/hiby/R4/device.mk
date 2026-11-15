#
# HiBy R4 (standard) - TWRP device configuration
# SPDX-License-Identifier: Apache-2.0
#

LOCAL_PATH := device/hiby/R4

# Virtual A/B (ro.virtual_ab.enabled=true on stock)
$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota.mk)

PRODUCT_USE_DYNAMIC_PARTITIONS := true
PRODUCT_SHIPPING_API_LEVEL := 30

# fastbootd inside TWRP (stock recovery's fastbootd is replaced when TWRP is flashed)
PRODUCT_PACKAGES += \
    android.hardware.fastboot@1.0-impl-mock \
    fastbootd

# Deliberately NO boot control HAL: TWRP must not be able to switch slots
# (slot b has no system/vendor in super on this unit and would not boot).
