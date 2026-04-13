from utils.vercel_adapter import make_handler
from utils.route_handlers import user_databus_room

class handler(make_handler(
    get_fn=user_databus_room,
)):
    pass