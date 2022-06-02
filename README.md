## Community Extension

To submit an extension, you need to create a Github repo for it and make a PR to this repo.

### In your own extension repo

E.g. https://github.com/tonsky/roam-calculator:

1. Provide `README.md` (required)
2. Provide `extension.js` (required)
3. Provide `extension.css` (optional)
4. Provide `CHANGELOG.md` (optional)

If your extension has a build process, you can provide a single `build.sh` that should generate files above.

Your extension should export as default a map with `onload` and `onunload` functions
All state setup in `onload` should be removed in `onunload`

```javascript
export default {onload: () => {},
                onunload: () => {}}
```

### In this repo

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
  "tags": ["print", "test"],
  "source_url": "https://github.com/tonsky/roam-calculator",
  "source_repo": "https://github.com/tonsky/roam-calculator.git",
  "source_commit": "d5ecd16363975b2e7a097d46e5f411c95e16682d"
}
```

Then make a Pull Request with this change. After it’s merged, your extension will be published in the Roam Marketplace.

## Community Theme

We do not support themes yet, we hope to add them soon. Please do not submit a theme as an extension.
