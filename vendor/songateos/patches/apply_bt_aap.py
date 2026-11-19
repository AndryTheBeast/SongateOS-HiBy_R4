#!/usr/bin/env python3
"""SongateOS: AirPods (and other peers without ERTM) - open classic L2CAP channels in basic mode instead of refusing.

Apple's accessory protocol (AAP: noise control, ear detection, battery, ...) runs on a classic L2CAP channel in basic
mode. Android's L2CAP sockets prefer ERTM (enhanced retransmission); when the peer doesn't support ERTM,
l2c_fcr_chk_chan_modes() already switches the channel to basic mode (preferred_mode = 0) but then refuses it anyway,
so the AirPods channel never opened (SongateOS test 2026-11-17: "L2CAP - Peer does not support our desired channel
types"). LibrePods hooks this same function to always continue; here it continues in basic mode. Peers that do
support ERTM are unaffected.

Run in the tree: python3 apply_bt_aap.py ~/lineage21   (idempotent; the original is kept as l2c_fcr.cc.lineage)
"""
import os
import shutil
import sys

top = sys.argv[1]
f = os.path.join(top, 'packages/modules/Bluetooth/system/stack/l2cap/l2c_fcr.cc')
old = '''  if (!(p_ccb->p_lcb->peer_ext_fea & L2CAP_EXTFEA_ENH_RETRANS) &&
      p_ccb->p_rcb->ertm_info.preferred_mode == L2CAP_FCR_ERTM_MODE) {
    log::warn("L2CAP - Peer does not support our desired channel types");
    p_ccb->p_rcb->ertm_info.preferred_mode = 0;
    return false;
  }'''
new = '''  if (!(p_ccb->p_lcb->peer_ext_fea & L2CAP_EXTFEA_ENH_RETRANS) &&
      p_ccb->p_rcb->ertm_info.preferred_mode == L2CAP_FCR_ERTM_MODE) {
    // SongateOS: continue in basic mode instead of refusing (AirPods' AAP channel is basic mode only)
    log::warn("L2CAP - Peer does not support ERTM, using basic mode (SongateOS)");
    p_ccb->p_rcb->ertm_info.preferred_mode = L2CAP_FCR_BASIC_MODE;
    return true;
  }'''
s = open(f).read()
if 'using basic mode (SongateOS)' in s:
    print('already applied: l2c_fcr.cc basic mode')
    sys.exit(0)
if s.count(old) != 1:
    sys.exit('apply_bt_aap: expected block not found once in l2c_fcr.cc')
keep = f + '.lineage'
if not os.path.exists(keep):
    shutil.copy2(f, keep)
open(f, 'w', newline='\n').write(s.replace(old, new))
print('patched: l2c_fcr.cc (peers without ERTM: basic mode)')
