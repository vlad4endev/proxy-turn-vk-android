package yandex

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/randx"
)

const (
	telemostConfHost = "cloud-api.yandex.ru"
	requestTimeout   = 20 * time.Second
	wsTimeout        = 15 * time.Second
)

type conferenceResponse struct {
	RoomID              string `json:"room_id"`
	PeerID              string `json:"peer_id"`
	Credentials         string `json:"credentials"`
	ClientConfiguration struct {
		MediaServerURL string `json:"media_server_url"`
	} `json:"client_configuration"`
}

type helloRequest struct {
	UID   string       `json:"uid"`
	Hello helloPayload `json:"hello"`
}

type helloPayload struct {
	ParticipantMeta struct {
		Name string `json:"name"`
		Role string `json:"role"`
	} `json:"participantMeta"`
	ParticipantAttributes struct {
		Name string `json:"name"`
		Role string `json:"role"`
	} `json:"participantAttributes"`
	SendAudio              bool   `json:"sendAudio"`
	SendVideo              bool   `json:"sendVideo"`
	SendSharing            bool   `json:"sendSharing"`
	ParticipantID          string `json:"participantId"`
	RoomID                 string `json:"roomId"`
	ServiceName            string `json:"serviceName"`
	Credentials            string `json:"credentials"`
	SdkInitializationID    string `json:"sdkInitializationId"`
	DisablePublisher       bool   `json:"disablePublisher"`
	DisableSubscriber      bool   `json:"disableSubscriber"`
	DisableSubscriberAudio bool   `json:"disableSubscriberAudio"`
	SdkInfo                struct {
		Implementation string `json:"implementation"`
		Version        string `json:"version"`
		UserAgent      string `json:"userAgent"`
		HwConcurrency  int    `json:"hwConcurrency"`
	} `json:"sdkInfo"`
	CapabilitiesOffer map[string][]string `json:"capabilitiesOffer"`
}

type wssAck struct {
	Ack struct {
		Status struct {
			Code string `json:"code"`
		} `json:"status"`
	} `json:"ack"`
}

type wssResponse struct {
	ServerHello struct {
		RtcConfiguration struct {
			IceServers []struct {
				Urls       []string `json:"urls"`
				Username   string   `json:"username,omitempty"`
				Credential string   `json:"credential,omitempty"`
			} `json:"iceServers"`
		} `json:"rtcConfiguration"`
	} `json:"serverHello"`
}

func fetchTurnCreds(ctx context.Context, hash string, streamID int, log logx.Logger) (user, pass, addr string, err error) {
	l := logx.OrNop(log)
	path := fmt.Sprintf(
		"/telemost_front/v2/telemost/conferences/https%%3A%%2F%%2Ftelemost.yandex.ru%%2Fj%%2F%s/connection?next_gen_media_platform_allowed=false",
		hash,
	)
	endpoint := "https://" + telemostConfHost + path

	client := &http.Client{Timeout: requestTimeout}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return "", "", "", err
	}

	ua := "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Referer", "https://telemost.yandex.ru/")
	req.Header.Set("Origin", "https://telemost.yandex.ru")
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Client-Instance-Id", uuid.NewString())

	l.Infof("[STREAM %d] [Yandex] Requesting Telemost conference…", streamID)
	resp, err := client.Do(req)
	if err != nil {
		return "", "", "", err
	}
	defer resp.Body.Close()

	body, readErr := io.ReadAll(resp.Body)
	if readErr != nil {
		return "", "", "", readErr
	}
	if resp.StatusCode != http.StatusOK {
		bodyStr := string(body)
		if IsRealCaptchaChallenge(bodyStr) {
			return "", "", "", fmt.Errorf("yandex captcha required")
		}
		return "", "", "", fmt.Errorf("GetConference: status=%s body=%s", resp.Status, bodyStr)
	}

	var conf conferenceResponse
	if err := json.Unmarshal(body, &conf); err != nil {
		return "", "", "", fmt.Errorf("decode conf: %w", err)
	}
	if conf.ClientConfiguration.MediaServerURL == "" {
		return "", "", "", fmt.Errorf("missing media_server_url")
	}

	displayName := randomDisplayName()
	hello := helloRequest{UID: uuid.NewString()}
	hello.Hello.ParticipantMeta.Name = displayName
	hello.Hello.ParticipantMeta.Role = "SPEAKER"
	hello.Hello.ParticipantAttributes.Name = displayName
	hello.Hello.ParticipantAttributes.Role = "SPEAKER"
	hello.Hello.ParticipantID = conf.PeerID
	hello.Hello.RoomID = conf.RoomID
	hello.Hello.ServiceName = "telemost"
	hello.Hello.Credentials = conf.Credentials
	hello.Hello.SdkInitializationID = uuid.NewString()
	hello.Hello.SdkInfo.Implementation = "browser"
	hello.Hello.SdkInfo.Version = "5.15.0"
	hello.Hello.SdkInfo.UserAgent = ua
	hello.Hello.SdkInfo.HwConcurrency = 4
	hello.Hello.CapabilitiesOffer = defaultCapabilities()

	wsHeaders := http.Header{}
	wsHeaders.Set("Origin", "https://telemost.yandex.ru")
	wsHeaders.Set("User-Agent", ua)

	dialer := websocket.Dialer{HandshakeTimeout: wsTimeout}
	conn, wsResp, err := dialer.DialContext(ctx, conf.ClientConfiguration.MediaServerURL, wsHeaders)
	if err != nil {
		return "", "", "", fmt.Errorf("ws dial: %w", err)
	}
	if wsResp != nil && wsResp.Body != nil {
		defer wsResp.Body.Close()
	}
	defer conn.Close()

	if err := conn.WriteJSON(hello); err != nil {
		return "", "", "", fmt.Errorf("ws write: %w", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(wsTimeout))

	for {
		select {
		case <-ctx.Done():
			return "", "", "", ctx.Err()
		default:
		}

		_, msg, err := conn.ReadMessage()
		if err != nil {
			return "", "", "", fmt.Errorf("ws read: %w", err)
		}

		var ack wssAck
		if err := json.Unmarshal(msg, &ack); err == nil && ack.Ack.Status.Code != "" {
			continue
		}

		var wsResp wssResponse
		if err := json.Unmarshal(msg, &wsResp); err != nil {
			continue
		}
		for _, ice := range wsResp.ServerHello.RtcConfiguration.IceServers {
			for _, u := range ice.Urls {
				if !strings.HasPrefix(u, "turn:") && !strings.HasPrefix(u, "turns:") {
					continue
				}
				if strings.Contains(u, "transport=tcp") {
					continue
				}
				clean := strings.Split(u, "?")[0]
				address := strings.TrimPrefix(strings.TrimPrefix(clean, "turn:"), "turns:")
				l.Infof("[STREAM %d] [Yandex] TURN creds OK via %s", streamID, address)
				return ice.Username, ice.Credential, address, nil
			}
		}
	}
}

func defaultCapabilities() map[string][]string {
	return map[string][]string{
		"offerAnswerMode":             {"SEPARATE"},
		"initialSubscriberOffer":      {"ON_HELLO"},
		"slotsMode":                   {"FROM_CONTROLLER"},
		"simulcastMode":               {"DISABLED"},
		"selfVadStatus":               {"FROM_SERVER"},
		"dataChannelSharing":          {"TO_RTP"},
		"videoEncoderConfig":          {"NO_CONFIG"},
		"dataChannelVideoCodec":       {"VP8"},
		"bandwidthLimitationReason":   {"BANDWIDTH_REASON_DISABLED"},
		"sdkDefaultDeviceManagement":  {"SDK_DEFAULT_DEVICE_MANAGEMENT_DISABLED"},
		"joinOrderLayout":             {"JOIN_ORDER_LAYOUT_DISABLED"},
		"pinLayout":                   {"PIN_LAYOUT_DISABLED"},
		"sendSelfViewVideoSlot":       {"SEND_SELF_VIEW_VIDEO_SLOT_DISABLED"},
		"serverLayoutTransition":      {"SERVER_LAYOUT_TRANSITION_DISABLED"},
		"sdkPublisherOptimizeBitrate": {"SDK_PUBLISHER_OPTIMIZE_BITRATE_DISABLED"},
		"sdkNetworkLostDetection":     {"SDK_NETWORK_LOST_DETECTION_DISABLED"},
		"sdkNetworkPathMonitor":       {"SDK_NETWORK_PATH_MONITOR_DISABLED"},
		"publisherVp9":                {"PUBLISH_VP9_DISABLED"},
		"svcMode":                     {"SVC_MODE_DISABLED"},
		"subscriberOfferAsyncAck":     {"SUBSCRIBER_OFFER_ASYNC_ACK_DISABLED"},
		"svcModes":                    {"FALSE"},
		"reportTelemetryModes":        {"TRUE"},
		"keepDefaultDevicesModes":     {"TRUE"},
	}
}

var guestNames = []string{"Алексей", "Мария", "Иван", "Анна", "Дмитрий", "Елена", "Сергей", "Ольга"}

func randomDisplayName() string {
	return guestNames[randx.Intn(len(guestNames))]
}
