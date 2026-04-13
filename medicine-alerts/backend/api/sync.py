from utils.vercel_adapter import make_handler
from utils.route_handlers import sync_get, sync_post

handler = make_handler(
    get_fn=sync_get,
    post_fn=sync_post,
)