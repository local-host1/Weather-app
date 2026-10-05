import json
import copy
import uuid
import os
import sys
from pathlib import Path


class LocationStore:
    def __init__(self, path):
        self.path = Path(path)
        if getattr(sys,'frozen',False) and self.path.name=='weather_locations.json':
            self.path=Path(os.environ.get('LOCALAPPDATA',str(Path.home())))/'Usoft'/'Weather'/'weather_locations.json'
        self.data = {'locations': [], 'selected': None, 'remember': True, 'auto_refresh': False,
                     'always_on_top': False, 'compact_start': True, 'show_coordinates': True, 'fahrenheit': False,
                     'theme':'Midnight'}
        self.warning = None
        self.backup_invalid=False
        if self.path.exists():
            try:
                saved = json.loads(self.path.read_text(encoding='utf-8'))
                if not isinstance(saved, dict) or not isinstance(saved.get('locations'), list):
                    raise ValueError('Invalid location file')
                for location in saved['locations']:
                    if not isinstance(location, dict) or not all(
                        isinstance(location.get(key),str) and location[key].strip() for key in ('id', 'name', 'query')
                    ):
                        raise ValueError('Invalid saved location')
                for key in ('remember','auto_refresh','always_on_top','compact_start','show_coordinates','fahrenheit'):
                    if key in saved and not isinstance(saved[key],bool):
                        raise ValueError('Invalid saved preference')
                if saved.get('selected') is not None and not isinstance(saved['selected'],str):
                    raise ValueError('Invalid selected location')
                if saved.get('theme','Midnight') not in ('Midnight','Ocean','Light'):
                    raise ValueError('Invalid theme')
                self.data.update({key: saved[key] for key in self.data if key in saved})
            except (OSError, ValueError, TypeError):
                self.warning = 'Saved locations could not be read. The original file has been kept.'
                self.backup_invalid=True

    def save(self):
        self._write(self.data)

    def _write(self,data):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.backup_invalid and self.path.exists():
            backup=self.path.with_suffix('.invalid.json')
            if not backup.exists():
                backup.write_bytes(self.path.read_bytes())
            self.backup_invalid=False
        temporary = self.path.with_suffix('.tmp')
        temporary.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding='utf-8')
        temporary.replace(self.path)

    def update(self,**changes):
        candidate=copy.deepcopy(self.data)
        candidate.update(changes)
        self._write(candidate)
        self.data=candidate

    def add(self, weather):
        candidate=copy.deepcopy(self.data)
        query = f"{weather['latitude']}, {weather['longitude']}"
        entry = next((item for item in candidate['locations'] if item['query'] == query), None)
        if entry is None:
            entry = {'id': uuid.uuid4().hex, 'name': weather['location'], 'query': query}
            candidate['locations'].append(entry)
        elif weather.get('custom_label'):
            entry['name'] = weather['location']
        candidate['selected'] = entry['id']
        self._write(candidate)
        self.data=candidate
        return entry


