"""Stable Windows taskbar registration and frameless minimize/restore."""
import ctypes
from ctypes import wintypes
import tkinter as tk

user32=ctypes.WinDLL('user32',use_last_error=True)
for name,args,result in [
    ('GetAncestor',[wintypes.HWND,wintypes.UINT],wintypes.HWND),
    ('GetWindowLongW',[wintypes.HWND,ctypes.c_int],ctypes.c_long),
    ('SetWindowLongW',[wintypes.HWND,ctypes.c_int,ctypes.c_long],ctypes.c_long),
    ('GetClassLongW',[wintypes.HWND,ctypes.c_int],wintypes.DWORD),
    ('SetClassLongW',[wintypes.HWND,ctypes.c_int,wintypes.LONG],wintypes.DWORD),
    ('SetWindowPos',[wintypes.HWND,wintypes.HWND,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,wintypes.UINT],wintypes.BOOL),
    ('ShowWindow',[wintypes.HWND,ctypes.c_int],wintypes.BOOL),
    ('RedrawWindow',[wintypes.HWND,ctypes.c_void_p,ctypes.c_void_p,wintypes.UINT],wintypes.BOOL),
    ('IsIconic',[wintypes.HWND],wintypes.BOOL),
]:
    function=getattr(user32,name)
    function.argtypes=args
    function.restype=result


def hwnd(app):
    return user32.GetAncestor(app.winfo_id(),2)


def install_window(App,transparent):
    original_init=App.__init__
    original_icons=App.taskbar_style

    def taskbar_style(self):
        if getattr(self,'closing',False):
            return
        handle=hwnd(self)
        extended=user32.GetWindowLongW(handle,-20)
        user32.SetWindowLongW(handle,-20,(extended & ~0x80)|0x40000)
        # Reuse the existing application icon loader, then clear its shadow.
        original_icons(self)
        class_style=user32.GetClassLongW(handle,-26)
        user32.SetClassLongW(handle,-26,class_style & ~0x20000)
        style=user32.GetWindowLongW(handle,-16)
        user32.SetWindowLongW(handle,-16,style & ~0x00C40000)
        user32.SetWindowPos(handle,None,0,0,0,0,0x0037)
        self.attributes('-transparentcolor',transparent)
        if not getattr(self,'_taskbar_registered',False):
            self._taskbar_registered=True
            # Shell registration is refreshed immediately, without user input.
            user32.ShowWindow(handle,0)
            user32.ShowWindow(handle,5)
        user32.RedrawWindow(handle,None,None,0x0185)

    def minimize(self):
        self.hide_tip()
        user32.ShowWindow(hwnd(self),6)

    def restore_frame(self,event):
        if event.widget is not self or getattr(self,'closing',False):
            return
        if not user32.IsIconic(hwnd(self)):
            self.after_idle(self.taskbar_style)

    def init(self,*args,**kwargs):
        original_init(self,*args,**kwargs)
        self.configure(fg_color=transparent)
        self.after_idle(self.taskbar_style)

    App.__init__=init
    App.taskbar_style=taskbar_style
    App.minimize=minimize
    App.restore_frame=restore_frame

