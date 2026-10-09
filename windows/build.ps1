$ErrorActionPreference = 'Stop'
g++ receiver.cpp -o MyCastReceiver.exe -std=c++17 -O2 -static -static-libgcc -static-libstdc++ -municode -mwindows -lws2_32 -lgdiplus -lshlwapi -lole32
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
