package myapp;

import lombok.Data;

@Data
public class AaaReq {

    private kyotsuBu kyotsuBu;

    private kobetsuBu kobetsuBu;

    @Data()
    class kyotsuBu {

        private ifInfo ifInfo;

        @Data()
        class ifInfo {

            private String ifSeq;
        }
    }

    @Data()
    class kobetsuBu {

        private keiyakushaInfo keiyakushaInfo;

        private sekyusakiInfo sekyusakiInfo;

        @Data()
        class keiyakushaInfo {

            private String jyushoCode;

            private String banchi1;

            private String banchi2;

            private String banchi3;
        }

        @Data()
        class sekyusakiInfo {

            private String jyushoCode;

            private String banchi1;

            private String banchi2;

            private String banchi3;
        }
    }
}
