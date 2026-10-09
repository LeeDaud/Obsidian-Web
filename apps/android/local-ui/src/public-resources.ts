const hosts = ["tiles.openfreemap.org", "tile.openstreetmap.org", "nominatim.openstreetmap.org"];
export function publicResource(url: URL): boolean {
  return url.protocol === "https:" && !url.username && !url.password && !url.port && hosts.includes(url.hostname);
}
