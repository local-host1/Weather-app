# Build with Python 3.13: python -m PyInstaller Weather.spec
from pathlib import Path

root=Path(SPECPATH)
assets=root/'weather_assets'
datas=[(str(assets/name),'weather_assets') for name in
       ('weather-icon.png','weather.ico','weather_desktop-recovery.pyc')]
from PyInstaller.utils.hooks import collect_data_files
datas+=collect_data_files('customtkinter')
a=Analysis([str(root/'weather_gui.py')],pathex=[str(root)],datas=datas,
    hiddenimports=['customtkinter','PIL.Image','PIL.ImageDraw','PIL.ImageTk','PIL.ImageFont',
                   'tkinter','ctypes','math','queue','threading','datetime','weather_storage','weather_tools'],
    excludes=['langchain_core','langchain','langchain_community','langchain_ollama','langchain_openai',
              'ollama','openai','numpy','pandas','torch','matplotlib'],noarchive=False)
pyz=PYZ(a.pure)
exe=EXE(pyz,a.scripts,a.binaries,a.datas,[],name='Weather',debug=False,
        bootloader_ignore_signals=False,strip=False,upx=False,console=False,
        icon=str(assets/'weather.ico'))
