// Lightweight tests for the manifest import API helper.
// Mocks axios so no network is required (runs under CRA's built-in Jest).

const mockPost = jest.fn();
const mockGet = jest.fn();

jest.mock('axios', () => ({
  __esModule: true,
  default: {
    create: () => ({
      post: mockPost,
      get: mockGet,
      delete: jest.fn(),
      put: jest.fn(),
      interceptors: { response: { use: jest.fn() } },
    }),
  },
}));

describe('api.importManifest', () => {
  let api;

  beforeEach(() => {
    jest.resetModules();
    mockPost.mockReset();
    api = require('./api').api;
  });

  it('posts the file as multipart form data and returns result', async () => {
    mockPost.mockResolvedValue({
      data: { success: true, service: 'order-service', routesCreated: 1, contractsCreated: 3 },
    });

    const file = new Blob(['service:\n  name: order-service'], { type: 'text/yaml' });
    const result = await api.importManifest(file);

    expect(mockPost).toHaveBeenCalledTimes(1);
    const [url, body, config] = mockPost.mock.calls[0];
    expect(url).toBe('/import-manifest');
    expect(body).toBeInstanceOf(FormData);
    expect(config.headers['Content-Type']).toBe('multipart/form-data');
    expect(result.service).toBe('order-service');
    expect(result.routesCreated).toBe(1);
  });

  it('propagates validation errors from a rejected request', async () => {
    mockPost.mockRejectedValue(new Error('outbound[0].targetService is required'));

    const file = new Blob(['bad'], { type: 'text/yaml' });

    await expect(api.importManifest(file)).rejects.toThrow('targetService is required');
  });
});
