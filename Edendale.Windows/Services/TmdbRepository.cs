using System.Text.Json;
using Edendale.Windows.Core;
using Edendale.Windows.Models;

namespace Edendale.Windows.Services;

/// <summary>
/// Windows-owned TMDB domain service. It maps remote payloads into app models
/// and owns browse, detail, account, and synchronization rules.
/// </summary>
internal sealed class TmdbRepository
{
    private readonly TmdbClient _client = new();

    public bool IsConfigured => _client.IsConfigured;

    public async Task<HomeCatalog> LoadHomeAsync(IReadOnlyList<WatchProgress> progress)
    {
        var trendingTask = TrendingAsync();
        var moviesTask = PopularAsync("movie");
        var showsTask = PopularAsync("tv");
        var ratedTask = TopRatedAsync("movie");
        var genresTask = MovieGenresAsync();
        await Task.WhenAll(trendingTask, moviesTask, showsTask, ratedTask, genresTask);

        var trending = await trendingTask;
        var genres = await genresTask;
        var continueProgress = progress
            .Where(item => !item.IsCompleted)
            .OrderByDescending(item => item.LastWatchedEpochMillis)
            .FirstOrDefault(item =>
                item.MediaType == "movie" || (item.MediaType == "episode" && item.ShowTmdbId is not null));
        MediaRef? continueRef = continueProgress switch
        {
            { MediaType: "movie" } => new MediaRef
            {
                Id = continueProgress.TmdbId,
                MediaType = "movie",
            },
            { MediaType: "episode", ShowTmdbId: int showId } => new MediaRef
            {
                Id = showId,
                MediaType = "tv",
            },
            _ => null,
        };

        HeroScene? continueScene = null;
        if (continueRef is not null)
        {
            var detail = await OptionalAsync(() =>
                MediaDetailAsync(continueRef.Id, continueRef.MediaType));
            if (detail is not null)
            {
                continueScene = CreateHeroScene(detail, continueProgress);
            }
        }

        var spotlightTasks = trending
            .Where(item => continueRef is null
                || item.Id != continueRef.Id
                || item.MediaType != continueRef.MediaType)
            .Take(8)
            .Select(item => OptionalAsync(() => MediaDetailAsync(item.Id, item.MediaType)));
        var spotlights = (await Task.WhenAll(spotlightTasks))
            .Where(detail => detail is not null)
            .Select(detail => CreateHeroScene(detail!, null));

        var scenes = new List<HeroScene>();
        if (continueScene is not null) scenes.Add(continueScene);
        scenes.AddRange(spotlights);

        return new HomeCatalog
        {
            HeroScenes = scenes,
            Trending = trending,
            PopularMovies = await moviesTask,
            PopularShows = await showsTask,
            TopRated = await ratedTask,
            Genres = genres,
            Collections =
            [
                new() { Id = "all", Title = "All Archives" },
                new() { Id = "movies", Title = "Feature Films" },
                new() { Id = "shows", Title = "Series" },
                .. genres.Take(6).Select(genre => new CollectionFilter
                {
                    Id = $"genre:{genre.Id}",
                    Title = genre.Name,
                    GenreId = genre.Id,
                }),
            ],
        };
    }

    public Task<List<MediaItem>> LoadCollectionAsync(string filter)
    {
        if (filter == "all") return TrendingAsync("week");
        if (filter == "movies") return DiscoverAsync("movie");
        if (filter == "shows") return DiscoverAsync("tv");
        if (filter.StartsWith("genre:", StringComparison.Ordinal)
            && int.TryParse(filter[6..], out var genreId))
        {
            return DiscoverAsync("movie", genreId);
        }
        throw new WindowsCoreException($"Unknown collection filter \"{filter}\".");
    }

    public async Task<List<MediaItem>> SearchMediaAsync(string query)
    {
        if (string.IsNullOrWhiteSpace(query)) return [];
        var payload = await _client.GetAsync("/search/multi", new Dictionary<string, string>
        {
            ["query"] = query.Trim(),
            ["include_adult"] = "false",
        });
        return MapMediaItems(payload, "movie");
    }

    public async Task<List<PersonItem>> SearchPeopleAsync(string query)
    {
        if (string.IsNullOrWhiteSpace(query)) return [];
        var payload = await _client.GetAsync("/search/person", new Dictionary<string, string>
        {
            ["query"] = query.Trim(),
            ["include_adult"] = "false",
        });
        return payload.Array("results").Select(item =>
        {
            var path = item.String("profile_path");
            return new PersonItem
            {
                Id = item.Int("id") ?? 0,
                Name = item.String("name") ?? "",
                ProfilePath = path,
                ProfileUrl = ImageUrl(path, "w185"),
                KnownFor = item.Array("known_for")
                    .Select(known => known.String("title") ?? known.String("name"))
                    .Where(title => !string.IsNullOrWhiteSpace(title))
                    .Cast<string>()
                    .Take(3)
                    .ToList(),
            };
        }).Where(person => person.Id > 0 && person.Name.Length > 0).ToList();
    }

    public async Task<ScopedSearchResult> SearchScopedAsync(string raw)
    {
        var query = SearchQuery.Parse(raw);
        var result = new ScopedSearchResult
        {
            Scope = query.Scope,
            Term = query.Term,
            LeadsWithPeople = query.Scope == "people",
        };
        if (string.IsNullOrWhiteSpace(query.Term)) return result;

        switch (query.Scope)
        {
            case "people":
            {
                var peopleTask = SearchPeopleAsync(query.Term);
                var titlesTask = OptionalAsync(() => SearchMediaAsync(query.Term), []);
                await Task.WhenAll(peopleTask, titlesTask);
                result.People = await peopleTask;
                result.Titles = await titlesTask;
                break;
            }
            case "movies":
                result.Titles = await SearchTypeAsync(query.Term, "movie");
                break;
            case "shows":
                result.Titles = await SearchTypeAsync(query.Term, "tv");
                break;
            default:
            {
                var titlesTask = SearchMediaAsync(query.Term);
                var peopleTask = OptionalAsync(() => SearchPeopleAsync(query.Term), []);
                await Task.WhenAll(titlesTask, peopleTask);
                result.Titles = await titlesTask;
                result.People = await peopleTask;
                break;
            }
        }
        return result;
    }

    public Task<List<MediaItem>> TrendingAsync(string window = "day") =>
        ItemsFromEndpointAsync($"/trending/all/{window}", "movie");

    public async Task<PersonDetail> PersonDetailAsync(int personId)
    {
        if (personId <= 0) throw new ArgumentOutOfRangeException(nameof(personId));
        var item = await _client.GetAsync($"/person/{personId}");
        var birthday = item.NonBlankString("birthday");
        var deathday = item.NonBlankString("deathday");
        var place = item.NonBlankString("place_of_birth");
        var dates = birthday?.Length >= 4 && deathday?.Length >= 4
            ? $"{birthday[..4]} – {deathday[..4]}"
            : birthday?.Length >= 4
                ? birthday[..4]
                : deathday?.Length >= 4 ? $"– {deathday[..4]}" : null;
        var vitals = string.Join(" · ", new[] { dates, place }
            .Where(value => !string.IsNullOrWhiteSpace(value)));
        var profile = item.String("profile_path");
        return new PersonDetail
        {
            Id = item.Int("id") ?? personId,
            Name = item.String("name") ?? "Unknown",
            Biography = item.NonBlankString("biography"),
            ProfilePath = profile,
            ProfileUrl = ImageUrl(profile, "h632"),
            Birthday = birthday,
            Deathday = deathday,
            PlaceOfBirth = place,
            KnownForDepartment = item.NonBlankString("known_for_department"),
            Vitals = vitals.Length == 0 ? null : vitals,
        };
    }

    public async Task<Dictionary<string, int>> ReleaseCountsAsync(int year)
    {
        var counts = new Dictionary<string, int>(StringComparer.Ordinal);
        var seen = new HashSet<int>();
        for (var page = 1; page <= 10; page++)
        {
            var items = await MoviesReleasedPageAsync($"{year}-01-01", $"{year}-12-31", page);
            if (items.Count == 0) break;
            foreach (var item in items.Where(item => seen.Add(item.Id)))
            {
                if (item.ReleaseDate?.StartsWith(year.ToString(), StringComparison.Ordinal) == true)
                {
                    counts[item.ReleaseDate] = counts.GetValueOrDefault(item.ReleaseDate) + 1;
                }
            }
        }
        return counts;
    }

    public async Task<List<MediaItem>> DiscoverReleasedAsync(string from, string to)
    {
        var results = new List<MediaItem>();
        var seen = new HashSet<int>();
        for (var page = 1; page <= 3; page++)
        {
            var items = await MoviesReleasedPageAsync(from, to, page);
            if (items.Count == 0) break;
            results.AddRange(items.Where(item => seen.Add(item.Id)));
        }
        return results;
    }

    public async Task<MediaDetail> MediaDetailAsync(int id, string mediaType)
    {
        ValidateMediaType(mediaType);
        if (id <= 0) throw new ArgumentOutOfRangeException(nameof(id));
        var item = await _client.GetAsync($"/{mediaType}/{id}", new Dictionary<string, string>
        {
            ["append_to_response"] = "credits",
        });
        return mediaType == "movie" ? MapMovieDetail(item) : MapShowDetail(item);
    }

    public async Task<EpisodeDetail> EpisodeDetailAsync(int showId, int season, int episode)
    {
        if (showId <= 0 || season < 0 || episode <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(showId), "Episode coordinates are invalid.");
        }
        var item = await _client.GetAsync($"/tv/{showId}/season/{season}/episode/{episode}");
        return MapEpisode(item);
    }

    public async Task<SeasonDetail> SeasonDetailAsync(int showId, int seasonNumber)
    {
        if (showId <= 0 || seasonNumber < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(showId), "Season coordinates are invalid.");
        }
        var item = await _client.GetAsync($"/tv/{showId}/season/{seasonNumber}");
        var poster = item.String("poster_path");
        return new SeasonDetail
        {
            SeasonNumber = item.Int("season_number") ?? seasonNumber,
            Name = item.String("name") ?? $"Season {seasonNumber}",
            Overview = item.NonBlankString("overview"),
            AirDate = item.String("air_date"),
            PosterPath = poster,
            PosterUrl = ImageUrl(poster, "w342"),
            Episodes = item.Array("episodes").Select(MapEpisode).ToList(),
        };
    }

    public async Task<List<MediaItem>> FilmographyAsync(int personId)
    {
        if (personId <= 0) throw new ArgumentOutOfRangeException(nameof(personId));
        var payload = await _client.GetAsync($"/person/{personId}/combined_credits");
        return MapMediaItems(payload.Array("cast"), "movie")
            .GroupBy(item => $"{item.MediaType}:{item.Id}", StringComparer.Ordinal)
            .Select(group => group.First())
            .OrderByDescending(item => item.ReleaseDate ?? "")
            .ToList();
    }

    public async Task<TrailerVideo?> BestTrailerAsync(int id, string mediaType)
    {
        ValidateMediaType(mediaType);
        var payload = await _client.GetAsync($"/{mediaType}/{id}/videos");
        var videos = payload.Array("results").Select(item => new TrailerVideo
        {
            Id = item.String("id") ?? "",
            Key = item.String("key") ?? "",
            Name = item.String("name"),
            Site = item.String("site"),
            Type = item.String("type"),
            Official = item.Bool("official"),
        }).Where(video => video.Id.Length > 0 && video.Key.Length > 0).ToList();
        var youtube = videos.Where(video => video.Site == "YouTube").ToList();
        return youtube.FirstOrDefault(video => video.Type == "Trailer" && video.Official == true)
            ?? youtube.FirstOrDefault(video => video.Type == "Trailer")
            ?? youtube.FirstOrDefault(video => video.Type == "Teaser");
    }

    public async Task<TmdbAuthStart> BeginAuthenticationAsync()
    {
        var payload = await _client.GetAsync("/authentication/token/new");
        var token = payload.String("request_token")
            ?? throw new WindowsCoreException("TMDB response is missing the request token.");
        return new TmdbAuthStart
        {
            RequestToken = token,
            ApprovalUrl = $"https://www.themoviedb.org/authenticate/{token}",
        };
    }

    public async Task<TmdbSessionInfo> FinishAuthenticationAsync(string requestToken)
    {
        var sessionPayload = await _client.SendAsync(
            HttpMethod.Post,
            "/authentication/session/new",
            jsonBody: new Dictionary<string, object> { ["request_token"] = requestToken });
        var sessionId = sessionPayload.String("session_id")
            ?? throw new WindowsCoreException("TMDB response is missing the session id.");
        var account = await _client.GetAsync("/account", new Dictionary<string, string>
        {
            ["session_id"] = sessionId,
        });
        return new TmdbSessionInfo
        {
            SessionId = sessionId,
            AccountId = account.Int("id")
                ?? throw new WindowsCoreException("TMDB response is missing the account id."),
            Username = account.String("username"),
            Name = account.NonBlankString("name"),
        };
    }

    public async Task LogoutAsync(string sessionId)
    {
        _ = await _client.SendAsync(
            HttpMethod.Delete,
            "/authentication/session",
            jsonBody: new Dictionary<string, object> { ["session_id"] = sessionId });
    }

    public async Task<UserMediaSyncOutcome> SyncUserMediaAsync(
        string sessionId,
        int accountId,
        IReadOnlyList<UserMediaRecord> local)
    {
        var favouritesTask = LoadAccountItemsAsync(sessionId, accountId, "favorite/movies", "movie");
        var favouriteShowsTask = LoadAccountItemsAsync(sessionId, accountId, "favorite/tv", "tv");
        var watchlistTask = LoadAccountItemsAsync(sessionId, accountId, "watchlist/movies", "movie");
        var watchlistShowsTask = LoadAccountItemsAsync(sessionId, accountId, "watchlist/tv", "tv");
        var ratingsTask = LoadAccountRatingsAsync(sessionId, accountId, "rated/movies", "movie");
        var showRatingsTask = LoadAccountRatingsAsync(sessionId, accountId, "rated/tv", "tv");
        await Task.WhenAll(
            favouritesTask, favouriteShowsTask, watchlistTask, watchlistShowsTask,
            ratingsTask, showRatingsTask);

        var favourites = (await favouritesTask).Concat(await favouriteShowsTask)
            .ToDictionary(ItemKey, StringComparer.Ordinal);
        var watchlist = (await watchlistTask).Concat(await watchlistShowsTask)
            .ToDictionary(ItemKey, StringComparer.Ordinal);
        var ratings = (await ratingsTask).Concat(await showRatingsTask)
            .ToDictionary(item => ItemKey(item.Item), StringComparer.Ordinal);
        var localKeys = local.Select(record => record.StorageKey).ToHashSet(StringComparer.Ordinal);
        var remoteOnly = favourites.Values
            .Concat(watchlist.Values)
            .Concat(ratings.Values.Select(rating => rating.Item))
            .Where(item => !localKeys.Contains(ItemKey(item)))
            .GroupBy(ItemKey, StringComparer.Ordinal)
            .Select(group => group.First())
            .Select(item => new UserMediaRecord
            {
                TmdbId = item.Id,
                MediaType = item.MediaType,
                Title = item.Title,
                PosterPath = item.PosterPath,
                PosterUrl = item.PosterUrl,
            });

        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var pushed = 0;
        var pulled = 0;
        var records = new List<UserMediaRecord>();
        foreach (var record in local.Select(CloneRecord).Concat(remoteOnly))
        {
            var key = record.StorageKey;
            var remoteFavourite = favourites.ContainsKey(key);
            if (record.FavouriteDirty)
            {
                if (record.Favourite != remoteFavourite)
                {
                    await SetAccountFlagAsync(
                        sessionId, accountId, record, "favorite", "favorite", record.Favourite);
                    pushed++;
                }
                record.FavouriteDirty = false;
            }
            else if (record.Favourite != remoteFavourite)
            {
                record.Favourite = remoteFavourite;
                record.FavouriteUpdatedAt = now;
                pulled++;
            }

            var remoteWatchlisted = watchlist.ContainsKey(key);
            if (record.WatchlistDirty)
            {
                if (record.Watchlist != remoteWatchlisted)
                {
                    await SetAccountFlagAsync(
                        sessionId, accountId, record, "watchlist", "watchlist", record.Watchlist);
                    pushed++;
                }
                record.WatchlistDirty = false;
            }
            else if (record.Watchlist != remoteWatchlisted)
            {
                record.Watchlist = remoteWatchlisted;
                record.WatchlistUpdatedAt = now;
                pulled++;
            }

            var remoteRating = ratings.GetValueOrDefault(key)?.Rating;
            if (record.RatingDirty)
            {
                if (record.Rating != remoteRating)
                {
                    await SetRatingAsync(sessionId, record, record.Rating);
                    pushed++;
                }
                record.RatingDirty = false;
            }
            else if (record.Rating != remoteRating)
            {
                record.Rating = remoteRating;
                record.RatingUpdatedAt = now;
                pulled++;
            }

            var display = favourites.GetValueOrDefault(key)
                ?? watchlist.GetValueOrDefault(key)
                ?? ratings.GetValueOrDefault(key)?.Item;
            if (display is not null)
            {
                record.Title = display.Title;
                record.PosterPath = display.PosterPath;
                record.PosterUrl = display.PosterUrl;
            }
            if (record.HasState) records.Add(record);
        }

        return new UserMediaSyncOutcome { Records = records, Pushed = pushed, Pulled = pulled };
    }

    private Task<List<MediaItem>> PopularAsync(string mediaType) =>
        ItemsFromEndpointAsync($"/{mediaType}/popular", mediaType);

    private Task<List<MediaItem>> TopRatedAsync(string mediaType) =>
        ItemsFromEndpointAsync($"/{mediaType}/top_rated", mediaType);

    private async Task<List<MediaItem>> DiscoverAsync(string mediaType, int? genreId = null)
    {
        var parameters = new Dictionary<string, string>
        {
            ["sort_by"] = "popularity.desc",
            ["include_adult"] = "false",
        };
        if (genreId is int id) parameters["with_genres"] = id.ToString();
        var payload = await _client.GetAsync($"/discover/{mediaType}", parameters);
        return MapMediaItems(payload, mediaType);
    }

    private async Task<List<MediaItem>> SearchTypeAsync(string term, string mediaType)
    {
        var payload = await _client.GetAsync($"/search/{mediaType}", new Dictionary<string, string>
        {
            ["query"] = term,
            ["include_adult"] = "false",
        });
        return MapMediaItems(payload, mediaType);
    }

    private async Task<List<MediaItem>> ItemsFromEndpointAsync(string path, string defaultType)
    {
        var payload = await _client.GetAsync(path);
        return MapMediaItems(payload, defaultType);
    }

    private async Task<List<Genre>> MovieGenresAsync()
    {
        var payload = await _client.GetAsync("/genre/movie/list");
        return payload.Array("genres")
            .Select(item => new Genre
            {
                Id = item.Int("id") ?? 0,
                Name = item.String("name") ?? "",
            })
            .Where(genre => genre.Id > 0 && genre.Name.Length > 0)
            .ToList();
    }

    private async Task<List<MediaItem>> MoviesReleasedPageAsync(string from, string to, int page)
    {
        var payload = await _client.GetAsync("/discover/movie", new Dictionary<string, string>
        {
            ["sort_by"] = "popularity.desc",
            ["include_adult"] = "false",
            ["primary_release_date.gte"] = from,
            ["primary_release_date.lte"] = to,
            ["page"] = page.ToString(),
        });
        return MapMediaItems(payload, "movie");
    }

    private async Task<List<MediaItem>> LoadAccountItemsAsync(
        string sessionId,
        int accountId,
        string segment,
        string mediaType)
    {
        var result = new List<MediaItem>();
        await ForEachAccountPageAsync(sessionId, accountId, segment, payload =>
        {
            result.AddRange(MapMediaItems(payload.Array("results"), mediaType));
        });
        return result;
    }

    private async Task<List<RatedItem>> LoadAccountRatingsAsync(
        string sessionId,
        int accountId,
        string segment,
        string mediaType)
    {
        var result = new List<RatedItem>();
        await ForEachAccountPageAsync(sessionId, accountId, segment, payload =>
        {
            var items = MapMediaItems(payload.Array("results"), mediaType)
                .ToDictionary(item => item.Id);
            foreach (var raw in payload.Array("results"))
            {
                if (raw.Int("id") is not int id || !items.TryGetValue(id, out var item)) continue;
                if (SanitizeRating(raw.Double("rating")) is double rating)
                {
                    result.Add(new RatedItem(item, rating));
                }
            }
        });
        return result;
    }

    private async Task ForEachAccountPageAsync(
        string sessionId,
        int accountId,
        string segment,
        Action<JsonElement> consume)
    {
        for (var page = 1; page <= 20; page++)
        {
            var payload = await _client.GetAsync($"/account/{accountId}/{segment}",
                new Dictionary<string, string>
                {
                    ["session_id"] = sessionId,
                    ["page"] = page.ToString(),
                    ["sort_by"] = "created_at.asc",
                });
            consume(payload);
            if (page >= (payload.Int("total_pages") ?? 1)) break;
        }
    }

    private Task<JsonElement> SetAccountFlagAsync(
        string sessionId,
        int accountId,
        UserMediaRecord record,
        string endpoint,
        string property,
        bool value) =>
        _client.SendAsync(
            HttpMethod.Post,
            $"/account/{accountId}/{endpoint}",
            new Dictionary<string, string> { ["session_id"] = sessionId },
            new Dictionary<string, object>
            {
                ["media_type"] = record.MediaType,
                ["media_id"] = record.TmdbId,
                [property] = value,
            });

    private Task<JsonElement> SetRatingAsync(
        string sessionId,
        UserMediaRecord record,
        double? value)
    {
        var path = $"/{record.MediaType}/{record.TmdbId}/rating";
        var parameters = new Dictionary<string, string> { ["session_id"] = sessionId };
        var rating = SanitizeRating(value);
        return rating is null
            ? _client.SendAsync(HttpMethod.Delete, path, parameters)
            : _client.SendAsync(
                HttpMethod.Post,
                path,
                parameters,
                new Dictionary<string, object> { ["value"] = rating.Value });
    }

    private static List<MediaItem> MapMediaItems(JsonElement payload, string defaultType) =>
        MapMediaItems(payload.Array("results"), defaultType);

    private static List<MediaItem> MapMediaItems(
        IEnumerable<JsonElement> payload,
        string defaultType)
    {
        var results = new List<MediaItem>();
        foreach (var item in payload)
        {
            var type = item.String("media_type") ?? defaultType;
            if (type is not ("movie" or "tv") || item.Int("id") is not int id) continue;
            var poster = item.String("poster_path");
            var backdrop = item.String("backdrop_path");
            var releaseDate = item.String("release_date") ?? item.String("first_air_date");
            results.Add(new MediaItem
            {
                Id = id,
                MediaType = type,
                Title = item.String("title") ?? item.String("name") ?? "Untitled",
                Overview = item.String("overview"),
                PosterPath = poster,
                BackdropPath = backdrop,
                PosterUrl = ImageUrl(poster, "w342"),
                BackdropUrl = ImageUrl(backdrop, "w780"),
                VoteAverage = item.Double("vote_average"),
                ReleaseDate = releaseDate,
                Year = Year(releaseDate),
            });
        }
        return results;
    }

    private static MediaDetail MapMovieDetail(JsonElement item)
    {
        var credits = item.Property("credits");
        var director = credits?.Array("crew")
            .FirstOrDefault(member => member.String("job") == "Director")
            .String("name");
        return MapDetail(
            item,
            "movie",
            item.String("title") ?? "Untitled",
            item.String("release_date"),
            item.Int("runtime"),
            director is null ? null : $"Directed by {director}",
            null,
            null,
            []);
    }

    private static MediaDetail MapShowDetail(JsonElement item)
    {
        var creator = item.Array("created_by").FirstOrDefault().String("name");
        var runtime = item.Array("episode_run_time")
            .Select(value => value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var number)
                ? (int?)number
                : null)
            .FirstOrDefault();
        var seasons = item.Array("seasons")
            .Select(season =>
            {
                var poster = season.String("poster_path");
                return new SeasonSummary
                {
                    SeasonNumber = season.Int("season_number") ?? -1,
                    Name = season.String("name") ?? "Season",
                    EpisodeCount = season.Int("episode_count"),
                    AirDate = season.String("air_date"),
                    PosterPath = poster,
                    PosterUrl = ImageUrl(poster, "w342"),
                    Overview = season.NonBlankString("overview"),
                };
            })
            .Where(season => season.SeasonNumber >= 0 && (season.EpisodeCount ?? 0) > 0)
            .OrderBy(season => season.SeasonNumber == 0)
            .ThenBy(season => season.SeasonNumber)
            .ToList();
        return MapDetail(
            item,
            "tv",
            item.String("name") ?? "Untitled",
            item.String("first_air_date"),
            runtime,
            creator is null ? null : $"Created by {creator}",
            item.Int("number_of_seasons"),
            item.Int("number_of_episodes"),
            seasons);
    }

    private static MediaDetail MapDetail(
        JsonElement item,
        string mediaType,
        string title,
        string? releaseDate,
        int? runtime,
        string? attribution,
        int? seasonCount,
        int? episodeCount,
        List<SeasonSummary> seasons)
    {
        var poster = item.String("poster_path");
        var backdrop = item.String("backdrop_path");
        var cast = item.Property("credits")?.Array("cast").Take(10).Select(member =>
        {
            var profile = member.String("profile_path");
            return new CastMember
            {
                Id = member.Int("id") ?? 0,
                Name = member.String("name") ?? "",
                Character = member.String("character"),
                ProfilePath = profile,
                ProfileUrl = ImageUrl(profile, "w185"),
            };
        }).Where(member => member.Id > 0 && member.Name.Length > 0).ToList() ?? [];

        return new MediaDetail
        {
            Ref = new MediaRef { Id = item.Int("id") ?? 0, MediaType = mediaType },
            Title = title,
            Tagline = item.NonBlankString("tagline"),
            Overview = item.NonBlankString("overview"),
            PosterPath = poster,
            BackdropPath = backdrop,
            PosterUrl = ImageUrl(poster, "w500"),
            BackdropUrl = ImageUrl(backdrop, "original"),
            Year = Year(releaseDate),
            RuntimeMinutes = runtime,
            Genres = item.Array("genres")
                .Select(genre => genre.String("name"))
                .Where(name => !string.IsNullOrWhiteSpace(name))
                .Cast<string>()
                .ToList(),
            Attribution = attribution,
            Score = item.Double("vote_average"),
            VoteCount = item.Int("vote_count"),
            Cast = cast,
            SeasonCount = seasonCount,
            EpisodeCount = episodeCount,
            Seasons = seasons,
        };
    }

    private static EpisodeDetail MapEpisode(JsonElement item)
    {
        var still = item.String("still_path");
        return new EpisodeDetail
        {
            Id = item.Int("id") ?? 0,
            Name = item.String("name") ?? "Untitled",
            Overview = item.String("overview"),
            StillPath = still,
            StillUrl = ImageUrl(still, "w780"),
            AirDate = item.String("air_date"),
            RuntimeMinutes = item.Int("runtime"),
            SeasonNumber = item.Int("season_number"),
            EpisodeNumber = item.Int("episode_number"),
            VoteAverage = item.Double("vote_average"),
        };
    }

    private static HeroScene CreateHeroScene(MediaDetail detail, WatchProgress? progress)
    {
        string? remaining = null;
        if (progress is not null && detail.RuntimeMinutes is > 0)
        {
            var seconds = (int)(detail.RuntimeMinutes.Value * 60 * (1 - Math.Clamp(progress.Position, 0, 1)));
            remaining = $"{seconds / 3600:00}:{seconds % 3600 / 60:00}:{seconds % 60:00} left";
        }
        return new HeroScene
        {
            Detail = detail,
            Progress = progress,
            IsContinueWatching = progress is not null,
            RemainingText = remaining,
        };
    }

    private static UserMediaRecord CloneRecord(UserMediaRecord record) => new()
    {
        TmdbId = record.TmdbId,
        MediaType = record.MediaType,
        Title = record.Title,
        PosterPath = record.PosterPath,
        PosterUrl = record.PosterUrl,
        Favourite = record.Favourite,
        FavouriteUpdatedAt = record.FavouriteUpdatedAt,
        FavouriteDirty = record.FavouriteDirty,
        Watchlist = record.Watchlist,
        WatchlistUpdatedAt = record.WatchlistUpdatedAt,
        WatchlistDirty = record.WatchlistDirty,
        Rating = record.Rating,
        RatingUpdatedAt = record.RatingUpdatedAt,
        RatingDirty = record.RatingDirty,
    };

    private static double? SanitizeRating(double? value) =>
        value is null ? null : Math.Clamp(Math.Truncate(value.Value * 2) / 2, 0.5, 10);

    private static string ItemKey(MediaItem item) => $"{item.MediaType}:{item.Id}";

    private static string? ImageUrl(string? path, string size) =>
        path is null ? null : $"https://image.tmdb.org/t/p/{size}{path}";

    private static int? Year(string? date) =>
        date?.Length >= 4 && int.TryParse(date[..4], out var year) ? year : null;

    private static void ValidateMediaType(string mediaType)
    {
        if (mediaType is not ("movie" or "tv"))
        {
            throw new WindowsCoreException($"Unsupported media type \"{mediaType}\".");
        }
    }

    private static async Task<T?> OptionalAsync<T>(Func<Task<T>> action) where T : class
    {
        try
        {
            return await action();
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            return null;
        }
    }

    private static async Task<T> OptionalAsync<T>(Func<Task<T>> action, T fallback)
    {
        try
        {
            return await action();
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            return fallback;
        }
    }

    private sealed record RatedItem(MediaItem Item, double Rating);
}
