from utils.vercel_adapter import make_handler
from utils.route_handlers import desktop_link_to_admin

handler = make_handler(
    post_fn=desktop_link_to_admin,
)