from utils.vercel_adapter import make_handler
from utils.route_handlers import admin_data

class handler(make_handler(
    get_fn=admin_data,
)):
    pass