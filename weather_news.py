"""Location-based RSS news and an expandable desktop feed."""
import html
import queue
import threading
import time
import webbrowser
import xml.etree.ElementTree as ET
from datetime import datetime,timedelta,timezone
from email.utils import parsedate_to_datetime
from urllib.parse import urlparse
import requests
import customtkinter as ctk
import tkinter as tk

_places={}
_place_lock=threading.Lock()
_last_lookup=0


def parse_feed(content,now=None):
    now=now or datetime.now(timezone.utc)
    stories=[]
    seen=set()
    for item in ET.fromstring(content).findall('./channel/item'):
        try:
            published=parsedate_to_datetime(item.findtext('pubDate',''))
            if published.tzinfo is None:
                published=published.replace(tzinfo=timezone.utc)
            title=html.unescape(item.findtext('title','')).strip()
            link=item.findtext('link','').strip()
            if not (now-timedelta(days=7)<=published<=now) or not title or urlparse(link).scheme!='https':
                continue
            source=item.findtext('source','Publisher')
            if title.endswith(' - '+source):
                title=title[:-len(source)-3]
            key=title.casefold()
            if key in seen:
                continue
            seen.add(key)
            stories.append({'title':title,'url':link,'source':source,'published':published.isoformat()})
        except (ValueError,TypeError,OverflowError):
            continue
    return sorted(stories,key=lambda item:item['published'],reverse=True)[:30]


def fetch_news(location):
    global _last_lookup
    key=location['query']
    with _place_lock:
        place=_places.get(key)
        if place is None:
            latitude,longitude=map(float,key.split(','))
            delay=1.1-(time.monotonic()-_last_lookup)
            if delay>0:
                time.sleep(delay)
            _last_lookup=time.monotonic()
            response=requests.get('https://nominatim.openstreetmap.org/reverse',
                params={'lat':latitude,'lon':longitude,'format':'jsonv2','zoom':10,'accept-language':'en'},
                headers={'User-Agent':'UsoftWeatherDesktop/1.0 (personal local weather and news reader)'},timeout=15)
            response.raise_for_status()
            address=response.json().get('address',{})
            city=next((address.get(key) for key in ('city','town','village','municipality','county','state') if address.get(key)),None)
            country=address.get('country','')
            if not city:
                raise ValueError('No nearby news area found for this location.')
            place=(city,country)
            _places[key]=place
    city,country=place
    response=requests.get('https://news.google.com/rss/search',
        params={'q':f'"{city}" "{country}" when:7d','hl':'en-US','gl':'US','ceid':'US:en'},timeout=20)
    response.raise_for_status()
    return {'area':', '.join(filter(None,place)),'stories':parse_feed(response.content)}


def install_news(ui):
    App=ui.WeatherApp
    original_show=App.show_page

    def request_news(self,force=False):
        location=dict(self.selected) if self.selected else None
        if not location:
            return
        key=location['id']
        if not hasattr(self,'news_queue'):
            self.news_queue=queue.Queue()
            self.news_results={}
            self.news_pending=set()
            self.news_open=set()
            self.after(100,lambda:poll_news(self))
        cached=self.news_results.get(key)
        if key in self.news_pending or (not force and cached and time.monotonic()-cached['fetched']<300):
            return
        self.news_pending.add(key)
        def worker():
            try:
                result=fetch_news(location)
            except (requests.RequestException,ValueError,ET.ParseError):
                result={'error':'News unavailable. Check your connection and try again.'}
            self.news_queue.put((key,result))
        threading.Thread(target=worker,daemon=True).start()

    def poll_news(self):
        if getattr(self,'closing',False):
            return
        try:
            while True:
                key,result=self.news_queue.get_nowait()
                result['fetched']=time.monotonic()
                self.news_results[key]=result
                self.news_pending.discard(key)
                if self.page=='weather' and self.selected and self.selected['id']==key:
                    update_badge(self)
                if self.page=='news' and self.selected and self.selected['id']==key:
                    self.show_page('news')
        except queue.Empty:
            pass
        self.after(100,lambda:poll_news(self))

    def show_page(self,page,preserve_draft=True):
        if page!='news':
            original_show(self,page,preserve_draft)
            if page=='weather':
                install_header_click(self)
            return
        # Reuse the existing panel frame, back control and theme handling.
        original_show(self,'info',preserve_draft)
        self.page='news'
        self.body.destroy()
        for widget in list(self.winfo_children()):
            if isinstance(widget,ctk.CTkLabel) and widget.cget('text')=='About':
                widget.configure(text='News Feed')
            elif isinstance(widget,ctk.CTkFrame) and widget.place_info().get('x')=='1':
                widget.destroy()
        self.panel_status.destroy()
        theme=self.store.data.get('theme','Midnight')
        from weather_views import PALETTES
        palette=PALETTES.get(theme,{})
        bg=palette.get(ui.BG,ui.BG)
        card_color=palette.get(ui.CARD,ui.CARD)
        muted=palette.get(ui.MUTED,ui.MUTED)
        text=palette.get(ui.TEXT,ui.TEXT)
        for widget in self.winfo_children():
            if isinstance(widget,ctk.CTkButton):
                color=palette.get('#0d131b','#0d131b') if widget.place_info().get('y')=='31' else bg
                widget.configure(bg_color=color)
        self.body=self.scroll(self,width=320,height=460)
        self.body.configure(fg_color=bg)
        self.body.place(x=13,y=85)
        request_news(self)
        location=self.selected
        result=getattr(self,'news_results',{}).get(location['id'],{}) if location else {}
        if not location:
            message='Choose a saved location to see local news.'
        elif location['id'] in self.news_pending:
            message='Loading local news from the past 7 days…'
        else:
            message=result.get('error') or (result.get('area',location['name'])+' · Past 7 days')
        self.label(self.body,message,11,color=muted,wraplength=288,justify='left',anchor='w').pack(fill='x',padx=10,pady=(4,13))
        for index,story in enumerate(result.get('stories',[])):
            card=ctk.CTkFrame(self.body,fg_color=card_color,corner_radius=10)
            card.pack(fill='x',padx=4,pady=(0,2))
            open_key=(location['id'],story['url'])
            expanded=open_key in self.news_open
            def toggle(key=open_key):
                self.news_open.symmetric_difference_update({key})
                self.show_page('news')
            row=self.button(card,self.fit_text(story['title'].upper(),247,12,True),toggle,width=290)
            row.configure(fg_color=card_color,hover_color=palette.get(ui.BORDER,ui.BORDER),
                text_color=text,font=self.font(12,True),anchor='w',height=34)
            row.pack(fill='x',padx=8,pady=(7,0))
            self.tip(row,story['title'])
            arrow=self.button(card,'',toggle,width=24,image=self.icons['up' if expanded else 'down'])
            arrow.configure(height=24,fg_color=card_color,hover_color=card_color)
            arrow.place(x=272,y=12)
            self.label(card,datetime.fromisoformat(story['published']).astimezone().strftime('%A, %B %d, %Y'),
                11,color=muted,anchor='w').pack(fill='x',padx=13,pady=(0,8))
            if expanded:
                self.label(card,story['title'],13,True,color=text,wraplength=272,justify='left',anchor='w').pack(fill='x',padx=13,pady=(4,8))
                self.label(card,story['source'],11,color=muted,anchor='w').pack(fill='x',padx=13,pady=(0,7))
                button=self.button(card,'Read article ↗',lambda link=story['url']:webbrowser.open(link),width=150)
                button.configure(fg_color=bg,text_color=text)
                button.pack(anchor='w',padx=13,pady=(0,12))
        if location and not result.get('stories') and not result.get('error') and location['id'] not in self.news_pending:
            self.label(self.body,'No matching news from the past 7 days.',12,color=muted,wraplength=280).pack(pady=20)
        refresh=self.button(self,'Refresh news',lambda:refresh_news(self),width=125)
        refresh.configure(fg_color=card_color,bg_color=bg,text_color=text)
        refresh.place(x=112,y=546)
        self.label(self,'Google News RSS · Area: © OpenStreetMap',9,color=muted,fg_color=bg).place(x=33,y=579)

    def refresh_news(self):
        request_news(self,True)
        self.show_page('news')

    def install_header_click(self):
        from weather_views import PALETTES
        palette=PALETTES.get(self.store.data.get('theme','Midnight'),{})
        self._news_hover=False
        self.news_badge=self.label(self,'',9,True,
            color=palette.get(ui.TEXT,ui.TEXT),fg_color=palette.get(ui.CARD,ui.CARD),
            bg_color=palette.get('#0d131b','#0d131b'),corner_radius=9,width=20,height=18)
        self.news_badge.bind('<Button-1>',lambda event:self.show_page('news'))
        self.news_badge.bind('<Enter>',lambda event:enter(self))
        self.news_badge.bind('<Leave>',lambda event:self.after(60,lambda:leave(self)))
        self.news_badge.configure(cursor='hand2')
        self.tip(self.news_badge,'News Feed · Past 7 days')
        def press(event):
            self._news_press=(event.x_root,event.y_root)
        def release(event):
            start=getattr(self,'_news_press',None)
            if not start or abs(event.x_root-start[0])+abs(event.y_root-start[1])>6:
                return
            x=event.x_root-self.winfo_rootx()
            y=event.y_root-self.winfo_rooty()
            if 46<=x<=240 and 17<=y<=72:
                self.show_page('news')
        for widget in self.winfo_children():
            title=isinstance(widget,ctk.CTkLabel) and widget.cget('image') is getattr(self,'wordmark_image',None)
            surface=isinstance(widget,tk.Canvas) and widget.place_info().get('y')=='0'
            if title or surface:
                widget.bind('<ButtonPress-1>',press,add='+')
                widget.bind('<ButtonRelease-1>',release,add='+')
                widget.bind('<Enter>',lambda event:motion(self,event),add='+')
                widget.bind('<Motion>',lambda event:motion(self,event),add='+')
                widget.bind('<Leave>',lambda event:self.after(60,lambda:leave(self)),add='+')
                if title:
                    widget.configure(cursor='hand2')
                    self.tip(widget,'Local news · Past 7 days')

    def update_badge(self):
        if not getattr(self,'_news_hover',False) or self.page!='weather':
            return
        key=self.selected['id'] if self.selected else None
        result=getattr(self,'news_results',{}).get(key,{})
        text='…' if key in getattr(self,'news_pending',set()) else '!' if result.get('error') else str(len(result.get('stories',[])))
        self.news_badge.configure(text=text)
        width=self.wordmark_image.cget('size')[0] if hasattr(self,'wordmark_image') else 95
        self.news_badge.place(x=60+width+4,y=23)

    def enter(self):
        if self.page!='weather':
            return
        self._news_hover=True
        request_news(self)
        update_badge(self)

    def motion(self,event):
        x=event.x_root-self.winfo_rootx()
        y=event.y_root-self.winfo_rooty()
        if 46<=x<=240 and 17<=y<=72:
            if not getattr(self,'_news_hover',False):
                enter(self)
        else:
            hide_badge(self)

    def hide_badge(self):
        self._news_hover=False
        if self.page=='weather' and hasattr(self,'news_badge') and self.news_badge.winfo_exists():
            self.news_badge.place_forget()

    def leave(self):
        if getattr(self,'closing',False) or self.page!='weather':
            return
        x,y=self.winfo_pointerxy()
        x-=self.winfo_rootx()
        y-=self.winfo_rooty()
        if not (46<=x<=240 and 17<=y<=72):
            hide_badge(self)

    App.show_page=show_page
    App.request_news=request_news
