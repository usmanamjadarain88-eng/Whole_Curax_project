from utils.vercel_adapter import make_handler
from utils.route_handlers import user_plans_get, user_plans_post


class handler(
    make_handler(
        get_fn=user_plans_get,
        post_fn=user_plans_post,
    )
):
    pass
