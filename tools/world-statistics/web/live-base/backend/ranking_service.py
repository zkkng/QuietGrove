"""Read-only rankings and character portraits. No account or inventory writes."""
import hashlib
import json
import os
import re
import threading
import time
from collections import OrderedDict
from datetime import datetime, timezone
from urllib.request import Request, urlopen
from flask import abort, jsonify, request, Response

CLASSES = {'all': ('All classes', None), 'beginner': ('Beginner', (0, 0)),
           'warrior': ('Warrior', (100, 199)), 'magician': ('Magician', (200, 299)),
           'bowman': ('Bowman', (300, 399)), 'thief': ('Thief', (400, 499)),
           'pirate': ('Pirate', (500, 599)), 'cygnus': ('Cygnus Knights', (1000, 1999)),
           'aran': ('Aran', (2000, 2199))}
JOBS = (0,100,110,111,112,120,121,122,130,131,132,200,210,211,212,220,221,222,230,231,232,
        300,310,311,312,320,321,322,400,410,411,412,420,421,422,500,510,511,512,520,521,522,
        1000,1100,1110,1111,1112,1200,1210,1211,1212,1300,1310,1311,1312,
        1400,1410,1411,1412,1500,1510,1511,1512,2000,2100,2110,2111,2112)
_portraits = OrderedDict()
_lock = threading.Lock()
_render_slots = threading.BoundedSemaphore(3)
RENDER_BASE = os.environ.get('QG_CHARACTER_RENDER_BASE', 'https://maplestory.io/api/GMS/83/character').rstrip('/')
MAX_PNG = 1024 * 1024


def visible_equipment(items):
    """Apply v83 cash-overlay slots; pet equips, rings and medals do not render."""
    ordinary, cash = {}, {}
    for item in items:
        position, item_id = -int(item['position']), int(item['itemid'])
        if not 1000000 <= item_id < 2000000:
            continue
        if 1 <= position <= 11:
            ordinary[position] = item_id
        elif 101 <= position <= 111:
            cash[position - 100] = item_id
    visible = dict(ordinary)
    visible.update(cash)
    # An overall replaces pants, even when a saved pair remains under it.
    if visible.get(5, 0) // 10000 == 105:
        visible.pop(6, None)
    weapon = ordinary.get(11, 0)
    stance = 'stand2' if 140 <= weapon // 10000 <= 149 else 'stand1'
    return [visible[k] for k in sorted(visible)], stance


def appearance(character, items):
    equipment, stance = visible_equipment(items)
    skin = int(character['skincolor'])
    if skin not in (0,1,2,3,4,5,9,10,11,12,13):
        return None
    hair, face = int(character['hair']), int(character['face'])
    if not (20000 <= face <= 99999 and 30000 <= hair <= 99999):
        return None
    look = {'skin': 2000 + skin, 'items': [hair, face] + equipment, 'stance': stance}
    revision = hashlib.sha256(json.dumps(look, sort_keys=True).encode()).hexdigest()[:20]
    return look, revision


def install_rankings(app, query, job_name, blocked_accounts):
    def visibility():
        clauses = ['c.gm=0', 'a.banned=0', 'a.tempban<=UTC_TIMESTAMP()', 'c.world=0',
                   'c.id<=20000', 'c.id<>999']
        params = []
        if blocked_accounts:
            clauses.append('c.accountid NOT IN (' + ','.join(['%s'] * len(blocked_accounts)) + ')')
            params.extend(blocked_accounts)
        return clauses, params

    def integer(name, default, low, high):
        raw = request.args.get(name, str(default))
        if not re.fullmatch(r'[0-9]{1,6}', raw):
            abort(400, description='Please enter a valid ' + name + '.')
        value = int(raw)
        if not low <= value <= high:
            abort(400, description='Please enter a valid ' + name + '.')
        return value

    def text_filter(name, maximum, pattern):
        value = request.args.get(name, '').strip()
        if len(value) > maximum or (value and not re.fullmatch(pattern, value)):
            abort(400, description='Please check the ' + name + ' filter.')
        return value

    def equips(ids):
        result = {key: [] for key in ids}
        if ids:
            rows = query('SELECT characterid,itemid,position FROM inventoryitems WHERE inventorytype=-1 AND characterid IN (' + ','.join(['%s'] * len(ids)) + ') AND position BETWEEN -111 AND -1 ORDER BY characterid,position', tuple(ids))
            for row in rows:
                result[row['characterid']].append(row)
        return result

    def class_for(job):
        return next((key for key, (_, span) in CLASSES.items() if span and span[0] <= job <= span[1]), 'all')

    def job_label(job):
        label = job_name(job)
        if job >= 1100:
            stage = {0:'I',10:'II',11:'III',12:'IV'}.get(job % 100)
            if stage:
                label += ' · ' + stage
        return label

    @app.get('/api/rankings')
    def character_rankings():
        page = integer('page', 1, 1, 10000)
        minimum = integer('minLevel', 1, 1, 200)
        maximum = integer('maxLevel', 200, 1, 200)
        kind, order = request.args.get('class', 'all'), request.args.get('sort', 'level')
        if kind not in CLASSES or order not in ('level', 'fame') or minimum > maximum:
            abort(400, description='Please check the ranking filters and level range.')
        exact = request.args.get('job', 'all')
        if exact != 'all' and (not exact.isdigit() or int(exact) not in JOBS):
            abort(400, description='Please select a valid job.')
        if exact != 'all' and kind != 'all' and class_for(int(exact)) != kind:
            abort(400, description='That job does not belong to the selected class.')
        search = text_filter('search', 13, r'[A-Za-z0-9]+')
        guild = text_filter('guild', 45, r'[A-Za-z0-9 _-]+')
        terms, params = visibility()
        if kind != 'all':
            terms.append('c.job BETWEEN %s AND %s')
            params.extend(CLASSES[kind][1])
        if exact != 'all':
            terms.append('c.job=%s'); params.append(int(exact))
        terms.append('c.level BETWEEN %s AND %s'); params.extend([minimum, maximum])
        if guild:
            terms.append('g.name LIKE %s'); params.append('%' + guild.replace('_', r'\_') + '%')
        sorting = 'c.level DESC,c.exp DESC,c.id ASC' if order == 'level' else 'c.fame DESC,c.level DESC,c.exp DESC,c.id ASC'
        ranked = ('SELECT c.id,c.name,c.level,c.job,c.fame,c.skincolor,c.gender,c.hair,c.face,'
                  "COALESCE(g.name,'') AS guild,ROW_NUMBER() OVER (ORDER BY " + sorting + ') AS rank '
                  'FROM characters c JOIN accounts a ON a.id=c.accountid LEFT JOIN guilds g ON g.guildid=c.guildid WHERE ' + ' AND '.join(terms))
        outer, values = (' WHERE ranked.name LIKE %s', params + ['%' + search + '%']) if search else ('', params)
        count = query('SELECT COUNT(*) AS n FROM (' + ranked + ') ranked' + outer, tuple(values))[0]['n']
        pages = max(1, (count + 19) // 20)
        page = min(page, pages)
        rows = query('SELECT * FROM (' + ranked + ') ranked' + outer + ' ORDER BY ranked.rank LIMIT 20 OFFSET %s', tuple(values + [(page - 1) * 20]))
        gear = equips([row['id'] for row in rows])
        players = []
        for row in rows:
            look = appearance(row, gear[row['id']])
            players.append({key: row[key] for key in ('id','name','level','job','fame','guild','rank')})
            players[-1].update(jobName=job_name(row['job']), className=CLASSES[class_for(row['job'])][0],
                               portraitUrl='/api/rankings/' + str(row['id']) + '/portrait?v=' + look[1] if look else None)
        return jsonify(players=players,total=count,page=page,pageSize=20,pages=pages,
                       filters={'classes':[{'id':key,'name':value[0]} for key,value in CLASSES.items()],
                                'jobs':[{'id':str(job),'name':job_label(job),'class':class_for(job)} for job in JOBS]},
                       updatedAt=datetime.now(timezone.utc).isoformat(),
                       source='Saved character data; staff and service accounts excluded. Name search preserves rank within the selected filters.')

    @app.get('/api/rankings/<int:character_id>/portrait')
    def character_portrait(character_id):
        terms, params = visibility()
        rows = query('SELECT c.id,c.skincolor,c.hair,c.face FROM characters c JOIN accounts a ON a.id=c.accountid WHERE ' + ' AND '.join(terms) + ' AND c.id=%s', tuple(params + [character_id]))
        if not rows:
            abort(404)
        look = appearance(rows[0], equips([character_id])[character_id])
        if not look:
            abort(404)
        config, revision = look
        now = time.monotonic()
        with _lock:
            cached = _portraits.get(revision)
            if cached and cached[0] > now:
                _portraits.move_to_end(revision)
                return Response(cached[1], mimetype='image/png')
        if not _render_slots.acquire(timeout=.1):
            abort(503, description='Character portraits are busy. Please try again shortly.')
        try:
            url = (RENDER_BASE + '/' + str(config['skin']) + '/' + ','.join(map(str, config['items']))
                   + '/' + config['stance'] + '/0?resize=2&padding=8')
            with urlopen(Request(url, headers={'User-Agent':'QuietGrove-CharacterPortraits/1.0'}), timeout=8) as response:
                png = response.read(MAX_PNG + 1)
            if len(png) > MAX_PNG or not png.startswith(b'\x89PNG\r\n\x1a\n'):
                raise ValueError('Invalid portrait response')
            with _lock:
                _portraits[revision] = (now + 3600, png)
                _portraits.move_to_end(revision)
                while len(_portraits) > 128:
                    _portraits.popitem(last=False)
            return Response(png, mimetype='image/png')
        except (OSError, ValueError):
            abort(503, description='Character art is temporarily unavailable.')
        finally:
            _render_slots.release()
