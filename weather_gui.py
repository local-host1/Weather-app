"""Desktop weather entry point."""
from weather_storage import LocationStore
from weather_desktop import WeatherApp
import sys
import json
from pathlib import Path

if __name__ == "__main__":
    if len(sys.argv)==3 and sys.argv[1] in ('--self-test','--self-test-live'):
        result_path=Path(sys.argv[2])
        try:
            from weather_window import hwnd,user32
            app=WeatherApp(settings_path=result_path.with_suffix('.settings.json'))
            app.update()
            assert app._taskbar_registered
            app.weather={'location':'Test City','temperature_c':20,'feels_like_c':19,
                'humidity_percent':60,'wind_kmh':5,'conditions':'Clear sky','timezone':'UTC',
                'time':'2026-10-05T12:00','hourly_forecast':[],'daily_forecast':[]}
            if sys.argv[1]=='--self-test-live':
                from weather_tools import current_weather
                from weather_news import fetch_news
                live=current_weather.invoke({'location':'6.9423, 79.9842'})
                assert 'error' not in live,live
                assert len(live['hourly_forecast'])==24 and len(live['daily_forecast'])==7
                app.weather=live
                news=fetch_news({'name':'Sydney','query':'-33.86785, 151.20732'})
                assert isinstance(news['stories'],list)
            app.store.update(theme='Ocean')
            assert LocationStore(app.store.path).data['theme']=='Ocean'
            for page in ('weather','settings','locations','details','news'):
                app.show_page(page)
                app.update()
            app.show_page('weather')
            handle=hwnd(app)
            app.minimize()
            app.update()
            assert user32.IsIconic(handle)
            user32.ShowWindow(handle,9)
            app.update()
            assert app.overrideredirect()
            app.close()
            result={'ok':True,'screens':5,'taskbar':True,'restore':True,'settings':True,
                    'live_data':sys.argv[1]=='--self-test-live','frozen':bool(getattr(sys,'frozen',False))}
        except Exception:
            import traceback
            result={'ok':False,'error':traceback.format_exc()}
        result_path.write_text(json.dumps(result,indent=2),encoding='utf-8')
    else:
        WeatherApp().mainloop()
