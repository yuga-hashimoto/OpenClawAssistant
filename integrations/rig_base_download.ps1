# Download Qwen3-TTS-12Hz-1.7B-Base model (voice clone) to the rig
$ErrorActionPreference = "Continue"
$py = 'C:\Users\jenni\wb-local\Scripts\python.exe'
$log = 'C:\Users\jenni\base_download.log'

"=== Downloading Qwen3-TTS-12Hz-1.7B-Base ===" | Out-File $log
& $py -c "from huggingface_hub import snapshot_download; p = snapshot_download('Qwen/Qwen3-TTS-12Hz-1.7B-Base', local_dir=r'C:\Users\jenni\wb-local\models\Base'); print('DONE', p)" 2>&1 | Out-File $log -Append
"=== EXIT: $LASTEXITCODE ===" | Out-File $log -Append
