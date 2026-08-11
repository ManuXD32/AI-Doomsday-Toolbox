# stable-diffusion.cpp ADetailer detector assets

These files are YOLOv8 **detection** checkpoints converted with the upstream
`stable-diffusion.cpp/scripts/convert_yolov8_to_safetensors.py` tool. They are not ordinary
Ultralytics SafeTensors files: the converter fuses BatchNorm layers and writes the tensor names
and `yolov8.*` metadata expected by the native GGML detector.

Sources and licenses:

- `face_yolov8n-sdcpp.safetensors`: `Bingsu/adetailer` `face_yolov8n.pt`, revision
  `53cc19de382014514d9d4038601d261a7faa9b7b`, Apache-2.0.
- `face_yolov8s-sdcpp.safetensors`: `Bingsu/adetailer` `face_yolov8s.pt`, the same revision,
  Apache-2.0.
- `hand_yolov8n-sdcpp.safetensors`: `Bingsu/adetailer` `hand_yolov8n.pt`, the same revision,
  Apache-2.0.
- `yolov8n-coco-sdcpp.safetensors`: official Ultralytics `v8.3.0` `yolov8n.pt` release asset,
  AGPL-3.0.

The app catalog pins each converted payload by exact byte size and SHA-256. Re-run the official
converter and update both values deliberately whenever an upstream checkpoint or converter is
changed.
