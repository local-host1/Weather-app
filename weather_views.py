"""Editable forecast views and persistent themes for the weather desktop."""
from datetime import datetime
import tkinter as tk
import customtkinter as ctk
from PIL import Image
from weather_tools import WEATHER_CODES
from weather_outline import smooth_outlines

PALETTES = {
    'Midnight': {},
    'Ocean': {'#090f17':'#081c2b','#0d131b':'#0c2537','#101722':'#102b40',
              '#1e242d':'#17374c','#303640':'#35566c','#252c36':'#244b64',
              '#171e27':'#0c2537','#18202b':'#17374c','#00dfb3':'#55d7ed'},
    'Light': {'#090f17':'#f3f5f8','#0d131b':'#e8edf3','#101722':'#edf1f6',
              '#1e242d':'#ffffff','#303640':'#c8d1dd','#252c36':'#dce3eb',
              '#171e27':'#e8edf3','#18202b':'#e1e7ef','#f6f7f9':'#142131',
              '#999fa7':'#566779','#00dfb3':'#007f70','#ff9696':'#b83a42',
              '#353b45':'#d4dde8','#d9dde2':'#d4dde8'},
}


def weather_symbol(code, is_day=True):
    if code is None:
        return '—'
    if code <= 1:
        return '☀' if is_day else '☾'
    if code <= 3:
        return '☁'
    if code in (45,48):
        return '≋'
    if code >= 95:
        return 'ϟ'
    if code in (71,73,75,77,85,86):
        return '❄'
    return '☂'


def install_views(ui):
    App=ui.WeatherApp
    original_show=App.show_page
    original_settings=App.build_settings
    original_panel=App.build_panel

    def apply_theme(self):
        palette=PALETTES.get(self.store.data.get('theme','Midnight'),{})
        def mapped(value):
            if isinstance(value,(tuple,list)):
                return tuple(mapped(part) for part in value)
            return palette.get(value.lower(),value) if isinstance(value,str) else value
        def walk(widget):
            if isinstance(widget,tk.Canvas):
                for key in ('bg',):
                    value=widget.cget(key)
                    replacement=mapped(value)
                    if replacement!=value:
                        widget.configure(**{key:replacement})
                for item in widget.find_all():
                    for key in ('fill','outline'):
                        try:
                            value=widget.itemcget(item,key)
                            replacement=mapped(value)
                            if replacement!=value:
                                widget.itemconfigure(item,**{key:replacement})
                        except tk.TclError:
                            pass
            elif isinstance(widget,ctk.CTkBaseClass):
                if isinstance(widget,ctk.CTkLabel) and self.store.data.get('theme')=='Light':
                    image=widget.cget('image')
                    if image is not None and image is getattr(self,'wordmark_image',None):
                        source=image.cget('light_image').convert('RGBA')
                        dark=Image.new('RGBA',source.size,'#142131')
                        dark.putalpha(source.getchannel('A'))
                        self.wordmark_image=ctk.CTkImage(light_image=dark,dark_image=dark,size=image.cget('size'))
                        widget.configure(image=self.wordmark_image)
                for key in ('fg_color','bg_color','text_color','border_color','hover_color',
                            'progress_color','button_color','button_hover_color',
                            'scrollbar_fg_color','scrollbar_button_color','scrollbar_button_hover_color',
                            'placeholder_text_color','text_color_disabled','dropdown_fg_color',
                            'dropdown_text_color','dropdown_hover_color'):
                    try:
                        value=widget.cget(key)
                        replacement=mapped(value)
                        if replacement!=value:
                            widget.configure(**{key:replacement})
                    except (ValueError,AttributeError,tk.TclError):
                        pass
            for child in widget.winfo_children():
                walk(child)
        walk(self)

    def show_page(self,page,preserve_draft=True):
        original_show(self,page,preserve_draft)
        apply_theme(self)
        smooth_outlines(self)

    def choose_theme(self,name):
        try:
            self.store.update(theme=name)
            self.set_status('Theme saved')
        except OSError:
            self.set_status('Could not save theme. Check disk space.',True)
        self.show_page(self.page)

    def build_settings(self):
        card=self.card(self.body)
        self.label(card,'Appearance',12,True,anchor='w').pack(fill='x',padx=13,pady=(12,4))
        self.label(card,'Choose your app theme.',11,color=ui.MUTED,anchor='w').pack(fill='x',padx=13,pady=(0,8))
        selector=ctk.CTkOptionMenu(card,values=list(PALETTES),height=30,
            fg_color=ui.BG,button_color=ui.BORDER,button_hover_color=ui.CARD,
            text_color=ui.TEXT,font=self.font(12),command=lambda name:choose_theme(self,name))
        selector.set(self.store.data.get('theme','Midnight'))
        selector.pack(fill='x',padx=13,pady=(0,12))
        self.theme_selector=selector
        original_settings(self)

    def build_panel(self,page):
        original_panel(self,page)
        for child in self.winfo_children():
            if isinstance(child,ctk.CTkFrame) and child.place_info().get('x')=='1':
                button=self.button(child,'◐',lambda:self.show_page('settings'),width=32)
                button.configure(height=32,corner_radius=16,font=self.font(20),fg_color=ui.CARD)
                button.place(x=14,y=162)
                self.tip(button,'Change theme')
                self.theme_sidebar_button=button
                break

    def build_details(self):
        self.details_tab=getattr(self,'details_tab','Overview')
        weather=self.weather or {}
        location=self.selected['name'] if self.selected else weather.get('location','Your location')
        self.label(self,self.fit_text(location.split(', ')[0],230,21,True),21,True,
            fg_color=ui.BG,anchor='w').place(x=22,y=58)
        self.label(self,weather.get('conditions','Select a location to see its forecast'),11,
            color=ui.MUTED,fg_color=ui.BG).place(x=22,y=89)
        temperature=self.temperature(weather['temperature_c']) if weather.get('temperature_c') is not None else '—'
        self.label(self,temperature,30,True,fg_color=ui.BG).place(x=22,y=115)
        if weather.get('feels_like_c') is not None:
            self.label(self,'Feels '+self.temperature(weather['feels_like_c']),11,color=ui.MUTED,fg_color=ui.BG).place(x=164,y=128)
        tabs=ctk.CTkSegmentedButton(self,values=['Overview','Hourly','7 Days'],height=32,width=306,dynamic_resizing=False,
            fg_color=ui.CARD,selected_color=ui.ACCENT,selected_hover_color='#00bfa0',
            unselected_color=ui.CARD,unselected_hover_color=ui.BORDER,text_color=ui.TEXT,
            font=self.font(12),command=lambda value:select_tab(self,value))
        tabs.set(self.details_tab)
        tabs.place(x=22,y=166)
        self.forecast_tabs=tabs
        self.body=self.scroll(self,width=314,height=506)
        self.body.place(x=14,y=211)
        self.detail_values={}
        if not weather:
            self.label(self.body,'Add a location to load weather and forecasts.',12,color=ui.MUTED,
                wraplength=270).pack(pady=35)
        elif self.details_tab=='Hourly':
            build_hourly(self,weather)
        elif self.details_tab=='7 Days':
            build_daily(self,weather)
        else:
            build_overview(self,weather)
        self.label(self,weather.get('timezone','Local time')+' · Open-Meteo',10,color=ui.MUTED,
            fg_color=ui.BG,width=306,anchor='center').place(x=22,y=723)
        back=self.button(self,'Back',lambda:self.show_page('weather'),width=90)
        back.place(x=130,y=746)

    def select_tab(self,value):
        self.details_tab=value
        self.show_page('details')

    def section(self,title):
        self.label(self.body,title,12,True,anchor='w').pack(fill='x',padx=8,pady=(5,10))

    def number(weather,key,unit=''):
        value=weather.get(key)
        return f'{value:.1f}{unit}' if value is not None else 'Unavailable'

    def build_overview(self,weather):
        grid=ctk.CTkFrame(self.body,fg_color='transparent')
        grid.pack(fill='x',padx=4)
        grid.grid_columnconfigure((0,1),weight=1,uniform='metrics')
        aqi=weather.get('air_quality_us_aqi')
        air=next((title for maximum,title in [(50,'Good'),(100,'Moderate'),(150,'Sensitive groups'),(200,'Unhealthy'),(300,'Very unhealthy')] if aqi<=maximum),'Hazardous') if aqi is not None else 'No estimate available'
        uv=weather.get('uv_index_max')
        uv_level=('Low' if uv<3 else 'Moderate' if uv<6 else 'High' if uv<8 else 'Very high' if uv<11 else 'Extreme') if uv is not None else 'No estimate available'
        direction=weather.get('wind_direction')
        compass=['N','NE','E','SE','S','SW','W','NW'][round(direction/45)%8] if direction is not None else '—'
        values=[('Wind',number(weather,'wind_kmh'),'km/h · '+compass),
                ('Humidity',f"{weather.get('humidity_percent','—')}%",'Relative humidity'),
                ('UV Index',number(weather,'uv_index_max'),'Daily max · '+uv_level),
                ('Air Quality',str(aqi) if aqi is not None else 'Unavailable','US AQI · '+air),
                ('Visibility',number(weather,'visibility_km'),'km · hourly estimate'),
                ('Pressure',number(weather,'pressure_hpa'),'hPa · at surface')]
        for index,(title,value,note) in enumerate(values):
            card=ctk.CTkFrame(grid,fg_color=ui.CARD,corner_radius=14,border_width=1,border_color='#252c36')
            card.grid(row=index//2,column=index%2,sticky='nsew',padx=4,pady=4)
            self.label(card,title,11,True,color=ui.MUTED,anchor='w').pack(fill='x',padx=12,pady=(12,5))
            self.label(card,value,24 if value!='Unavailable' else 13,True,anchor='w').pack(fill='x',padx=12)
            self.label(card,note,10,color=ui.MUTED,anchor='w',wraplength=115,justify='left').pack(fill='x',padx=12,pady=(4,12))
            self.detail_values[title]=value
        sun=self.card(self.body)
        self.label(sun,'Sunrise & Sunset',12,True,anchor='w').pack(fill='x',padx=13,pady=(12,6))
        canvas=tk.Canvas(sun,width=278,height=52,bg=ui.CARD,highlightthickness=0)
        canvas.pack(padx=8)
        canvas.create_line(10,45,268,45,fill=ui.BORDER)
        canvas.create_arc(22,5,256,85,start=0,extent=180,style='arc',outline=ui.ACCENT,width=2)
        canvas.create_oval(131,2,145,16,fill='#ffd36b',outline='')
        def clock(key):
            return datetime.fromisoformat(weather[key]).strftime('%I:%M %p') if weather.get(key) else 'Unavailable'
        self.label(sun,f"Rise {clock('sunrise')}     Set {clock('sunset')}",11,color=ui.MUTED).pack(pady=(0,12))
        self.label(self.body,'Air quality: CAMS model estimate',10,color=ui.MUTED).pack(pady=(0,10))
        preview=self.card(self.body)
        self.label(preview,'Next Hours',12,True,anchor='w').pack(fill='x',padx=13,pady=(12,8))
        strip=ctk.CTkFrame(preview,fg_color='transparent')
        strip.pack(fill='x',padx=8,pady=(0,12))
        hours=weather.get('hourly_forecast',[])[:5]
        for i,hour in enumerate(hours):
            cell=ctk.CTkFrame(strip,fg_color='transparent')
            cell.pack(side='left',expand=True,fill='x')
            self.label(cell,'Now' if i==0 else datetime.fromisoformat(hour['time']).strftime('%I %p').lstrip('0'),10,color=ui.MUTED).pack()
            self.label(cell,weather_symbol(hour['weather_code'],hour.get('is_day',True)),24,color=ui.ACCENT).pack(pady=5)
            self.label(cell,short_temp(self,hour.get('temperature_c')),12,True).pack()
        if not hours:
            self.label(strip,'Forecast unavailable',12,color=ui.MUTED).pack()
        self.button(self.body,'View 7-day forecast',lambda:select_tab(self,'7 Days'),width=275).pack(pady=(0,10))

    def short_temp(self,value):
        if value is None:
            return '—'
        if self.store.data['fahrenheit']:
            value=value*9/5+32
        return f'{value:.0f}°'

    def build_hourly(self,weather):
        section(self,'HOURLY FORECAST · NEXT 24 HOURS')
        for i,hour in enumerate(weather.get('hourly_forecast',[])):
            card=self.card(self.body)
            row=ctk.CTkFrame(card,fg_color='transparent')
            row.pack(fill='x',padx=12,pady=10)
            stamp=datetime.fromisoformat(hour['time'])
            self.label(row,'Now' if i==0 else stamp.strftime('%I %p').lstrip('0'),12,True,width=52,anchor='w').pack(side='left')
            self.label(row,weather_symbol(hour['weather_code'],hour.get('is_day',True)),24,color=ui.ACCENT,width=36).pack(side='left')
            self.label(row,short_temp(self,hour.get('temperature_c')),18,True,width=55).pack(side='left')
            rain=hour.get('rain_percent')
            self.label(row,f'{rain}% rain' if rain is not None else '—',11,color=ui.MUTED).pack(side='right')
            self.label(card,stamp.strftime('%a %d %b')+' · '+WEATHER_CODES.get(hour['weather_code'],'Unknown'),10,
                color=ui.MUTED,anchor='w',wraplength=265).pack(fill='x',padx=12,pady=(0,10))
        if not weather.get('hourly_forecast'):
            self.label(self.body,'Hourly forecast unavailable. Refresh weather.',12,color=ui.MUTED,wraplength=270).pack(pady=20)

    def build_daily(self,weather):
        section(self,'7-DAY FORECAST')
        for i,day in enumerate(weather.get('daily_forecast',[])):
            card=self.card(self.body)
            row=ctk.CTkFrame(card,fg_color='transparent')
            row.pack(fill='x',padx=12,pady=(10,4))
            self.label(row,'Today' if i==0 else datetime.fromisoformat(day['date']).strftime('%a %d'),12,True,width=65,anchor='w').pack(side='left')
            self.label(row,weather_symbol(day['weather_code']),25,color=ui.ACCENT,width=45).pack(side='left')
            self.label(row,short_temp(self,day.get('high_c'))+' / '+short_temp(self,day.get('low_c')),16,True).pack(side='right')
            rain=day.get('rain_percent')
            text=WEATHER_CODES.get(day['weather_code'],'Unknown')+' · '+(f'{rain}% rain' if rain is not None else 'Rain unavailable')
            self.label(card,text,10,color=ui.MUTED,anchor='w',wraplength=270).pack(fill='x',padx=12,pady=(0,11))
        if not weather.get('daily_forecast'):
            self.label(self.body,'Daily forecast unavailable. Refresh weather.',12,color=ui.MUTED,wraplength=270).pack(pady=20)

    App.show_page=show_page
    App.build_settings=build_settings
    App.build_panel=build_panel
    App.build_details=build_details
    App.choose_theme=choose_theme

