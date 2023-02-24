# Community Extension

[Full Documentation](https://roamresearch.com/#/app/developer-documentation/page/5BB8h4I7b)

To submit an extension, you need to create a GitHub repo for it and make a PR to this repo.

## In your own extension repo

E.g. https://github.com/tonsky/roam-calculator:

1. Provide `README.md` (required)
2. Provide `extension.js` (required)
3. Provide `extension.css` (optional)
4. Provide `CHANGELOG.md` (optional)

### extension.js

Your extension should export as default a map with `onload` and `onunload` functions.

All state setup in `onload` should be removed in `onunload`.

```javascript
export default {
  onload: () => {},
  onunload: () => {}
};
```

### build.sh

If `build.sh` exists in the root of your repo, it will be invoked before looking for `extension.js`/`extension.css` files.

The environment it’ll be invoked in is `ubuntu-20.04` from GitHub Actions. Consult [this](https://github.com/actions/virtual-environments/blob/main/images/linux/Ubuntu2004-Readme.md) to see what is available.

If your build script requires anything extra (e.g. libraries from NPM), it should download them as a part of `build.sh` execution.

## In this repo

1. Fork this repo
2. Create metadata file in `extensions/<your username>/<your repo>.json`

In a case of calculator, you would create

`extensions/tonsky/roam-calculator.json`

with the following content:

```json
{
  "name": "Test Extension 1",
  "short_description": "Prints 'Test message 1'",
  "author": "Nikita Prokopov",
  "tags": ["print", "test"], //optional
  "source_url": "https://github.com/tonsky/roam-calculator",
  "source_repo": "https://github.com/tonsky/roam-calculator.git",
  "source_commit": "d5ecd16363975b2e7a097d46e5f411c95e16682d",
  "stripe_account": "acct_1LGASrQVCl6NYjck" // optional
}
```

Then make a Pull Request with this change. After it’s merged, your extension will be published in the Roam Marketplace.

## Examples

 - [Bitcoin Price Tracker](https://github.com/panterarocks49/roam-extension-bitcoin-price)

# Community Theme

We do not support themes yet, we hope to add them soon. Please do not submit a theme as an extension.
