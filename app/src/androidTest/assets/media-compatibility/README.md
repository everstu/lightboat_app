Synthetic four-second blue H.264 baseline clip, encrypted as HLS AES-128.
Test key: bytes 0 through 15. IV: sixteen zero bytes. No external media.

Generated with FFmpeg: color=c=blue:s=96x64:r=10, -an -t 4 -c:v libx264
-profile:v baseline -pix_fmt yuv420p -g 40 -sc_threshold 0, HLS segment length 4.
