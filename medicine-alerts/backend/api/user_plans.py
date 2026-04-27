from utils.vercel_adapter import make_handler
from utils.route_handlers import user_plans_delete, user_plans_get, user_plans_patch, user_plans_post

class handler(
    make_handler(
        delete_fn=user_plans_delete,
        get_fn=user_plans_get,
        patch_fn=user_plans_patch,
        post_fn=user_plans_post,
    )
):
    pass