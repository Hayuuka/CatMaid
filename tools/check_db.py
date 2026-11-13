import sqlite3, os, datetime, sys

db = r'd:\work\女仆插件\testserver\plugins\CraftEngine\data.db'
print('mtime:', datetime.datetime.fromtimestamp(os.path.getmtime(db)))
con = sqlite3.connect(db)
tables = [n for (n,) in con.execute("select name from sqlite_master where type='table'")]
for n in tables:
    print('TABLE:', n)
    try:
        cols = [c[1] for c in con.execute(f'pragma table_info({n})')]
        print('  cols:', cols)
        for row in con.execute(f'select * from {n} limit 10'):
            print('  row:', row)
    except Exception as e:
        print('  err:', e)
