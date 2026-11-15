#
# HiBy R4 (standard) - TWRP product
# SPDX-License-Identifier: Apache-2.0
#

$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)
$(call inherit-product, vendor/twrp/config/common.mk)
$(call inherit-product, device/hiby/R4/device.mk)

PRODUCT_DEVICE := R4
PRODUCT_NAME := twrp_R4
PRODUCT_BRAND := HiBy
PRODUCT_MODEL := R4
PRODUCT_MANUFACTURER := HiBy

PRODUCT_GMS_CLIENTID_BASE := android-hiby
