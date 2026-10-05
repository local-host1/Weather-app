"""Weather desktop UI, recovered from its verified Python 3.13 build.

The implementation snapshot is retained locally after a disk-full source write.
The geometry refinements below remain editable Python source.
"""
import marshal
import os
import types
from pathlib import Path

_recovery = Path(__file__).parent / 'weather_assets' / 'weather_desktop-recovery.pyc'
_implementation=marshal.loads(_recovery.read_bytes()[16:])
# Optional personal-use font; distribution uses Windows' existing font.
_font_file=Path(__file__).parent/'weather_assets'/'ZegionDemo-Regular.ttf'
if not _font_file.exists():
    _fallback=str(Path(os.environ.get('WINDIR','C:/Windows'))/'Fonts'/'GOTHICB.TTF')
    def _replace_font(code):
        return code.replace(co_consts=tuple(_replace_font(value) if isinstance(value,types.CodeType)
            else _fallback if value=='ZegionDemo-Regular.ttf' else value for value in code.co_consts))
    _implementation=_replace_font(_implementation)
exec(_implementation, globals())


def rounded_path(vertices, radius=9):
    """Circular, tangent corners with straight connecting edges."""
    vertices=list(vertices)
    if len(vertices)==8 and vertices[0]==(2,16):
        vertices=[(2,16),(253.34,16),(262,1),(348,1),(348,256),(99.66,256),(91,271),(2,271)]
    path=[]
    for i,vertex in enumerate(vertices):
        previous,following=vertices[i-1],vertices[(i+1)%len(vertices)]
        before,after=math.dist(previous,vertex),math.dist(vertex,following)
        incoming=tuple((vertex[a]-previous[a])/before for a in (0,1))
        outgoing=tuple((following[a]-vertex[a])/after for a in (0,1))
        turn=math.atan2(incoming[0]*outgoing[1]-incoming[1]*outgoing[0],incoming[0]*outgoing[0]+incoming[1]*outgoing[1])
        if abs(turn)<1e-6:
            path.extend(vertex)
            continue
        tangent=math.tan(abs(turn)/2)
        distance=min(radius*tangent,before/2,after/2)
        actual_radius=distance/tangent
        entry=tuple(vertex[a]-incoming[a]*distance for a in (0,1))
        sign=1 if turn>0 else -1
        center=(entry[0]-incoming[1]*actual_radius*sign,entry[1]+incoming[0]*actual_radius*sign)
        start=math.atan2(entry[1]-center[1],entry[0]-center[0])
        for step in range(17):
            angle=start+turn*step/16
            path.extend((center[0]+actual_radius*math.cos(angle),center[1]+actual_radius*math.sin(angle)))
    return path


_original_weather=WeatherApp.build_weather
_original_chrome=WeatherApp.chrome


def _chrome(self,height):
    _original_chrome(self,height)
    if self.page!='weather':
        return
    surface=next(widget for widget in self.winfo_children()
                 if isinstance(widget,tk.Canvas) and widget.place_info().get('y')=='0')
    outer=surface.find_all()[0]
    surface.itemconfigure(outer,fill='#101722')
    # Replace the approximate header seam with one complete rounded layer.
    for item in list(surface.find_all()):
        if surface.type(item)=='line' and max(surface.coords(item)[1::2],default=0)<=72:
            surface.delete(item)
        elif surface.type(item)=='polygon' and surface.itemcget(item,'fill')=='#0d131b':
            surface.delete(item)
    header=rounded_path([(2,16),(253.34,16),(221,72),(2,72)],9)
    surface.create_polygon(header,fill='#0d131b',outline='')
    # One open seam avoids a rounded inset at the straight left edge.
    seam=[2,72,213,72]
    for step in range(17):
        t=step/16
        seam.extend(((1-t)**2*213+2*(1-t)*t*221+t*t*225,
                     (1-t)**2*72+2*(1-t)*t*72+t*t*65))
    seam.extend((262,1))
    surface.create_line(seam,fill=BORDER,width=1)
    surface.create_polygon(self.outline_points,fill='',outline=BORDER,width=1)
    for widget in self.winfo_children():
        if isinstance(widget,ctk.CTkButton) and int(widget.place_info().get('y',-1))==16:
            widget.configure(bg_color='#101722')
            if widget.cget('fg_color')==BG:
                widget.configure(fg_color='#101722')
    self.close_button.configure(fg_color='#0d131b')

WeatherApp.chrome=_chrome

def _build_weather(self,height):
    _original_weather(self,height)
    for widget in self.winfo_children():
        if isinstance(widget,tk.Canvas) and widget.winfo_manager()=='place' and int(widget.place_info().get('y',-1))==219:
            widget.configure(width=347,height=61)
            widget.place_configure(y=200)
            widget.delete('all')
            base=[value-(2 if index%2==0 else 200)
                  for index,value in enumerate(self.outline_points)]
            widget.create_polygon(base,fill='#101722',outline=BORDER,width=1)
            # The shared right edge stays straight; it has no inset corner.
            widget.create_polygon(130,0,346,0,346,56,97.66,56,
                                  fill='#0d131b',outline='')
            seam=[346,0,136,0]
            for step in range(17):
                t=step/16
                seam.extend(((1-t)**2*136+2*(1-t)*t*130+t*t*126.54,
                             t*t*6))
            seam.extend((97.66,56))
            widget.create_line(seam,fill=BORDER,width=1)
            widget.create_line(97.66,55.5,338,55.5,fill=BORDER,width=1)
            corner=[]
            for step in range(17):
                angle=math.pi/2*(1-step/16)
                corner.extend((338+8*math.cos(angle),47.5+8*math.sin(angle)))
            widget.create_line(corner,fill=BORDER,width=1)
            widget.create_line(346,0,346,47.5,fill=BORDER,width=1)
        elif isinstance(widget,ctk.CTkFrame) and int(widget.place_info().get('y',-1))==73:
            widget.configure(width=344,height=127,fg_color='#101722',bg_color='#101722',border_width=0)
            widget.place_configure(x=3)
            for child in widget.winfo_children():
                if child.winfo_manager()!='place':
                    continue
                position=child.place_info().get('y')
                if position=='32':
                    child.place_configure(y=33)
                    if isinstance(child,ctk.CTkLabel):
                        child.configure(height=25)
                elif position=='59' and isinstance(child,ctk.CTkLabel):
                    child.configure(height=15)
                    child.place_configure(y=62)
                elif position=='82' and isinstance(child,ctk.CTkLabel):
                    child.configure(height=15)
                    child.place_configure(y=83)
                elif position=='113':
                    child.place_configure(y=104)
        elif isinstance(widget,ctk.CTkLabel) and widget.cget('text') in ('LOCATIONS',str(len(self.store.data['locations']))+' saved'):
            widget.configure(fg_color='#101722',bg_color='#101722')
        elif isinstance(widget,ctk.CTkLabel) and widget.cget('text')=='Usoft® ©2026':
            widget.destroy()
        elif self.expanded and isinstance(widget,ctk.CTkLabel) and widget.place_info().get('y')=='566':
            widget.destroy()
    # Match the control backgrounds to their layer without altering hover states.
    for widget in self.winfo_children():
        placement=widget.place_info()
        if widget is getattr(self,'refresh_button',None):
            widget.place_configure(y=72)
        elif isinstance(widget,ctk.CTkLabel) and str(widget.cget('text')).startswith('Feels '):
            widget.configure(width=94,height=18,anchor='center')
            widget.place_configure(x=251,y=151)
        if isinstance(widget,ctk.CTkButton) and 219<=int(placement.get('y',-1))<=256:
            widget.place_configure(y=212)
            if int(placement.get('x',-1)) in (123,160,197,234):
                widget.place_configure(x=int(placement['x'])+6)
            widget.configure(bg_color='#0d131b')
            if int(placement.get('x',-1))==197:
                widget.configure(fg_color='#0d131b')
            if widget.cget('fg_color')==BG:
                widget.configure(fg_color='#0d131b')
    if not self.expanded:
        self.status_label.configure(width=160)
        self.label(self,'Usoft®',10,color=TEXT,fg_color=BG,bg_color=BG,anchor='e',width=47).place(x=294,y=271)
    # Fill the one-pixel gap above the bottom layer with the body color.
    body_surface=next(widget for widget in self.winfo_children()
                      if isinstance(widget,tk.Canvas) and widget.place_info().get('y')=='0')
    body_surface.create_line(3,199,130,199,fill='#101722',width=2)
    bridge=ctk.CTkFrame(self,width=127,height=3,corner_radius=0,
                       fg_color='#101722',bg_color='#101722',border_width=0)
    bridge.place(x=3,y=198)
    if self.expanded:
        shelf=[2,271,85,271]
        for step in range(17):
            t=step/16
            shelf.extend(((1-t)**2*85+2*(1-t)*t*91+t*t*94.46,
                          (1-t)**2*271+2*(1-t)*t*271+t*t*265))
        shelf.extend((99.66,256))
        body_surface.create_line(shelf,fill=BORDER,width=1)

WeatherApp.build_weather=_build_weather

import sys
from weather_views import install_views
install_views(sys.modules[__name__])
from weather_window import install_window
install_window(WeatherApp,TRANSPARENT)
from weather_news import install_news
install_news(sys.modules[__name__])

