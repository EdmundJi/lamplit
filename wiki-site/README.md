# OpenWiki static site

This directory is the deployable, read-only export of `../openwiki`.

Production: <https://wiki-site-delta.vercel.app>

Regenerate it after an OpenWiki update:

```bash
scripts/export-openwiki-site.sh
```

The export contains only the generated Wiki graph and viewer assets. It does not
need repository source, OpenWiki credentials, or model credentials at runtime.

Deploy from this directory:

```bash
cd wiki-site
vercel deploy --prod
```

The Vercel project is linked locally through the ignored `.vercel/` directory.
The repository's default `origin` is Gitee, which Vercel cannot connect as a Git
provider, so production publication is currently an explicit CLI step rather
than an automatic push deployment.
