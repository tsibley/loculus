# loculus

Detailed documentation is available in each folder's README. This file contains a high-level overview of the project and shared documentation that is best kept in one place.

## Architecture

- Backend code is in `backend`, see [`backend/README.md`](/backend/README.md)
- Frontend code is in `website`, see [`website/README.md`](/website/README.md)
- Sequence and metadata processing pipeline is in [`preprocessing`](/preprocessing) folder, see [`preprocessing/specification.md`](/preprocessing/specification.md) 
- Deployment code is in `kubernetes`, see [`kubernetes/README.md`](/kubernetes/README.md).
  Check this for local development setup instructions.
- Authorization is performed by our own keycloak instance. see config in [`keycloak-image`](kubernetes/loculus/templates/keycloak-deployment.yaml) and [`realm-config`](kubernetes/loculus/templates/keycloak-config-map.yaml)

## GitHub Actions

While the documentation is still a work in progress, a look at the [`.github/workflows`](/.github/workflows) folder might be helpful:

- [`backend.yml`](/.github/workflows/backend.yml) runs the backend tests and builds the backend docker image
- [`website.yml`](/.github/workflows/website.yml) runs the website tests and builds the website docker image
- [`e2e-k3d.yml`](/.github/workflows/e2e-k3d.yml) runs the end-to-end tests

## Setting up docker

### Configure access to the private container registry

Follow this guide <https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry#authenticating-with-a-personal-access-token-classic>. In short:

1. Generate a GitHub personal access token (classic), e.g. by using this link: <https://github.com/settings/tokens/new?scopes=read:packages>
1. Run `export CR_PAT=YOUR_TOKEN` (replace `YOUR_TOKEN` with the token)
1. Run `echo $CR_PAT | docker login ghcr.io -u USERNAME --password-stdin` (Not sure what to put as username, just leaving it as `USERNAME` seemed to work)

### (ARM macOS only): Configure docker default architecture

If you are running on an ARM macOS machine, you need to configure docker to use the `linux/amd64` architecture by default to work with images pushed by others. To do this, run:

```bash
export DOCKER_DEFAULT_PLATFORM=linux/amd64
```

## Authorization
We use keycloak for authorization. The keycloak instance is deployed in the `loculus` namespace and exposed to the outside either under `localhost:8083` or `authentication.[your-argo-cd-path]`. The keycloak instance is configured with a realm called `loculusRealm` and a client called `test-cli`. The realm is configured to use the exposed url of keycloak as a [frontend url](https://www.keycloak.org/server/hostname).
For testing we added multiple users to the realm. The users are:
- `admin` with password `admin` (login under `your-exposed-keycloak-url/admin/master/console/`)
- `testuser` with password `testuser` (login under `your-exposed-keycloak-url/realms/loculusRealm/account/`)
- and more testusers, for each browser in the e2e test following the pattern: `testuser_[processId]_[browser]` with password `testuser_[processId]_[browser]` 
- These testusers will be added to the `testGroup` in the setup for e2e tests. If you change the number of browsers in the e2e test, you need to adapt `website/tests/playwrightSetup.ts` accordingly. 

## Contributing to Loculus

Contributions are very welcome!
Please see [`CONTRIBUTING.md`](https://github.com/pathoplexus/loculus/blob/main/CONTRIBUTING.md)
for more information or ping us in case you need help.
