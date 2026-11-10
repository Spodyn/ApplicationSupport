"""Explicit opt-in, read-only Slack sandbox identity/discovery probe. Never run in ordinary CI."""
import argparse
import json
import os
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-sandbox', action='store_true', required=True)
    parser.parse_args()
    token = os.environ.get('USI_SANDBOX_SLACK_BOT_TOKEN', '')
    team = os.environ.get('USI_SANDBOX_SLACK_TEAM_ID', '')
    if os.environ.get('CI') or not token or not team:
        raise SystemExit('Requires explicit local sandbox token and expected workspace; CI is refused')

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    opener = urllib.request.build_opener(NoRedirect)

    def call(method, http_method='POST'):
        request = urllib.request.Request('https://slack.com/api/' + method,
                                         headers={'Authorization': 'Bearer ' + token}, method=http_method)
        with opener.open(request, timeout=15) as response:
            result = json.loads(response.read(2_000_000))
        if result.get('ok') is not True:
            raise SystemExit('Sandbox provider rejected the probe; inspect scopes privately')
        return result

    try:
        if call('auth.test').get('team_id') != team:
            raise SystemExit('Sandbox workspace identity mismatch; discovery refused')
        result = call('conversations.list?exclude_archived=true&limit=10&types=public_channel,private_channel', 'GET')
        if not isinstance(result.get('channels'), list):
            raise SystemExit('Sandbox discovery response shape mismatch')
    except Exception:
        raise SystemExit('Sandbox request failed; no credentials or provider payload logged') from None
    print('PASS: sandbox identity and read-only discovery. Sending/webhooks/uploads NOT RUN.')


if __name__ == '__main__':
    main()
