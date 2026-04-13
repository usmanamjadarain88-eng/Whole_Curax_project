"""
Vercel Python preset requires a discoverable ASGI ``app`` (see Vercel build error:
"no python entrypoint"). Real HTTP routes are separate functions under ``api/*.py``
and ``vercel.json`` rewrites; do not route user traffic through this stub.
"""


async def app(scope, receive, send):
    if scope["type"] != "http":
        return
    body = (
        b'{"message":"Vercel entrypoint stub only; '
        b'use / , /api/welcome, /api/health, or other paths from vercel.json."}'
    )
    await send(
        {
            "type": "http.response.start",
            "status": 404,
            "headers": [
                (b"content-type", b"application/json; charset=utf-8"),
                (b"content-length", str(len(body)).encode("ascii")),
            ],
        }
    )
    await send({"type": "http.response.body", "body": body, "more_body": False})
