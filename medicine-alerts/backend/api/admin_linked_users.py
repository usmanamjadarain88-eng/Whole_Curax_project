from utils.vercel_adapter import make_handler
from utils.route_handlers import get_linked_users

class handler(
    make_handler(
        get_fn=get_linked_users,
    )
):
    pass