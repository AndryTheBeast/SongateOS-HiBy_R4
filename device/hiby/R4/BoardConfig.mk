#
# Recovery device tree for the HiBy R4 (standard model, SM6125 "trinket", eMMC). NOT for the R4 EVA.
# Builds TWRP (twrp-12.1 tree) and OrangeFox (fox_12.1 tree); OrangeFox-only options are in orangefox.mk.
# Partition layout, kernel and boot header values were read from a stock R4 (firmware with kernel 4.14.190-perf #9).
# SPDX-License-Identifier: Apache-2.0
#

DEVICE_PATH := device/hiby/R4

ALLOW_MISSING_DEPENDENCIES := true
BUILD_BROKEN_DUP_RULES := true
BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES := true

# Architecture
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_ABI2 :=
TARGET_CPU_VARIANT := generic
TARGET_CPU_VARIANT_RUNTIME := kryo260

TARGET_2ND_ARCH := arm
TARGET_2ND_ARCH_VARIANT := armv8-a
TARGET_2ND_CPU_ABI := armeabi-v7a
TARGET_2ND_CPU_ABI2 := armeabi
TARGET_2ND_CPU_VARIANT := generic
TARGET_2ND_CPU_VARIANT_RUNTIME := kryo260

TARGET_SUPPORTS_64_BIT_APPS := true

# Platform (SM6125 / "trinket", msm-id 0x1d3)
TARGET_BOARD_PLATFORM := trinket
TARGET_BOOTLOADER_BOARD_NAME := trinket
TARGET_NO_BOOTLOADER := true
QCOM_BOARD_PLATFORMS += trinket

# A/B with a dedicated recovery partition (recovery_a/recovery_b), Virtual A/B
AB_OTA_UPDATER := true
AB_OTA_PARTITIONS += boot dtbo product system system_ext vendor vbmeta vbmeta_system
BOARD_USES_RECOVERY_AS_BOOT := false
TARGET_NO_RECOVERY := false
BOARD_USES_METADATA_PARTITION := true

# Kernel: prebuilt only (no HiBy source published).
# kernel/dtb/dtbo taken unmodified from stock recovery_a (4.14.190-perf #9), see prebuilt/SOURCES.sha256
BOARD_BOOT_HEADER_VERSION := 2
BOARD_KERNEL_BASE := 0x00000000
BOARD_KERNEL_PAGESIZE := 4096
BOARD_KERNEL_OFFSET := 0x00008000
BOARD_RAMDISK_OFFSET := 0x01000000
BOARD_KERNEL_TAGS_OFFSET := 0x00000100
BOARD_DTB_OFFSET := 0x01f00000
BOARD_KERNEL_IMAGE_NAME := Image
BOARD_KERNEL_CMDLINE := console=ttyMSM0,115200n8 androidboot.hardware=qcom androidboot.console=ttyMSM0 androidboot.memcg=1 lpm_levels.sleep_disabled=1 video=vfb:640x400,bpp=32,memsize=3072000 msm_rtb.filter=0x237 service_locator.enable=1 swiotlb=1 earlycon=msm_geni_serial,0x4a90000 loop.max_part=7 cgroup.memory=nokmem,nosocket buildvariant=user

TARGET_PREBUILT_KERNEL := $(DEVICE_PATH)/prebuilt/kernel
BOARD_INCLUDE_RECOVERY_DTBO := true
BOARD_PREBUILT_RECOVERY_DTBOIMAGE := $(DEVICE_PATH)/prebuilt/dtbo.img

BOARD_MKBOOTIMG_ARGS += --header_version $(BOARD_BOOT_HEADER_VERSION)
BOARD_MKBOOTIMG_ARGS += --kernel_offset $(BOARD_KERNEL_OFFSET)
BOARD_MKBOOTIMG_ARGS += --ramdisk_offset $(BOARD_RAMDISK_OFFSET)
BOARD_MKBOOTIMG_ARGS += --tags_offset $(BOARD_KERNEL_TAGS_OFFSET)
BOARD_MKBOOTIMG_ARGS += --dtb_offset $(BOARD_DTB_OFFSET)
BOARD_MKBOOTIMG_ARGS += --dtb $(DEVICE_PATH)/prebuilt/dtb.img

# Partition sizes (from GPT, 512-byte sectors, eMMC)
BOARD_FLASH_BLOCK_SIZE := 262144
BOARD_BOOTIMAGE_PARTITION_SIZE := 67108864
BOARD_RECOVERYIMAGE_PARTITION_SIZE := 100663296
BOARD_DTBOIMG_PARTITION_SIZE := 25165824

# Dynamic partitions: super 6 GiB, group qti_dynamic_partitions max 6140 MiB
BOARD_SUPER_PARTITION_SIZE := 6442450944
BOARD_SUPER_PARTITION_GROUPS := qti_dynamic_partitions
BOARD_QTI_DYNAMIC_PARTITIONS_SIZE := 6438256640
BOARD_QTI_DYNAMIC_PARTITIONS_PARTITION_LIST := system system_ext product vendor

# Filesystems
BOARD_HAS_LARGE_FILESYSTEM := true
BOARD_SYSTEMIMAGE_PARTITION_TYPE := ext4
BOARD_USERDATAIMAGE_FILE_SYSTEM_TYPE := f2fs
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_SYSTEM_EXTIMAGE_FILE_SYSTEM_TYPE := ext4
TARGET_COPY_OUT_VENDOR := vendor
TARGET_COPY_OUT_PRODUCT := product
TARGET_COPY_OUT_SYSTEM_EXT := system_ext
TARGET_USERIMAGES_USE_EXT4 := true
TARGET_USERIMAGES_USE_F2FS := true

# AVB: like stock, recovery gets an unsigned hash footer only (vbmeta is NOT touched)
BOARD_AVB_ENABLE := true

# Security patch (vendor)
VENDOR_SECURITY_PATCH := 2021-09-05

# Recovery
TARGET_RECOVERY_FSTAB := $(DEVICE_PATH)/recovery.fstab
TARGET_RECOVERY_PIXEL_FORMAT := RGBX_8888
TARGET_RECOVERY_QCOM_RTC_FIX := true
BOARD_HAS_NO_SELECT_BUTTON := true
BOARD_SUPPRESS_SECURE_ERASE := true
TARGET_USES_MKE2FS := true

# TWRP
TW_THEME := portrait_hdpi
TW_EXTRA_LANGUAGES := false
TW_SCREEN_BLANK_ON_BOOT := true
TW_INPUT_BLACKLIST := "hbtp_vm"
TW_BRIGHTNESS_PATH := "/sys/class/backlight/panel0-backlight/brightness"
TW_MAX_BRIGHTNESS := 4095
TW_DEFAULT_BRIGHTNESS := 2000
TW_FRAMERATE := 60
TW_USE_TOOLBOX := true
TW_INCLUDE_REPACKTOOLS := true
TW_INCLUDE_RESETPROP := true
TW_INCLUDE_LIBRESETPROP := true
TW_INCLUDE_FASTBOOTD := true
TW_EXCLUDE_TWRPAPP := true
TW_NO_EXFAT_FUSE := true
TW_DEFAULT_EXTERNAL_STORAGE := true
# lptools: the SongateOS update zip uses it to grow system_a inside super when a new system image is larger
TW_INCLUDE_LPTOOLS := true

# SongateOS OTA: recovery/root/system/bin/runatboot.sh turns /metadata/songateos/ota.cmd into an
# OpenRecoveryScript; recovery/root/system/etc/security/songateos_otacerts.zip = the release certificate
# (public), used for the signature check of update zips started that way (see ota/README.md).

# USB: kernel uses configfs; TWRP's default init.recovery.usb.rc is legacy android_usb only,
# which never binds the UDC for "mtp,adb" (no USB at all). Own configfs rc in recovery/root.
TW_EXCLUDE_DEFAULT_USB_INIT := true
RECOVERY_SDCARD_ON_DATA := true

# No userdata decryption yet (FBE + metadata encryption): use microSD or USB OTG storage
TW_INCLUDE_CRYPTO := false

# Battery: the R4's fuel gauge is /sys/class/power_supply/cw2015 (there is no "battery" node, so the default
# reader showed a constant 100 %). Read capacity/status straight from it; there is no health HAL in recovery.
TW_CUSTOM_BATTERY_PATH := "/sys/class/power_supply/cw2015"
TW_USE_LEGACY_BATTERY_SERVICES := true

# Debugging
TWRP_INCLUDE_LOGCAT := true
TARGET_USES_LOGD := true

# OrangeFox: its options are only read when this tree is built inside an OrangeFox (fox_12.1) source tree
ifneq ($(wildcard bootable/recovery/orangefox.mk),)
include $(DEVICE_PATH)/orangefox.mk
endif
