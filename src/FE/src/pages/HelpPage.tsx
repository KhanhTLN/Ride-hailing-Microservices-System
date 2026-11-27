import { ChevronDown, LifeBuoy } from "lucide-react";
export function HelpPage() {
  return (
    <div className="content-page help-page">
      <div className="page-heading">
        <span className="section-icon">
          <LifeBuoy />
        </span>
        <h1>Đồng hành cùng bạn</h1>
        <p className="muted">Một vài điều hữu ích trước khi lên đường.</p>
      </div>
      {[
        [
          "Làm sao để đặt một chuyến xe?",
          "Nhập điểm đón và điểm đến, chọn đúng địa điểm trong danh sách gợi ý. Chọn loại xe, xem giá rồi nhấn Đặt chuyến ngay. Theo dõi tiến trình trên trang chuyến đi.",
        ],
        [
          "Giá và thời gian dự kiến có thay đổi không?",
          "Giá được giữ trong 120 giây. Khi bạn đặt chuyến thành công, giá được lưu cho chuyến đi đó. Thời gian di chuyển là ước tính theo tuyến đường và chưa tính giao thông trực tiếp. Tuyến xe máy hiện dùng mô hình đường ô tô.",
        ],
        [
          "Tôi có thể hủy chuyến khi nào?",
          "Hành khách có thể hủy trước khi chuyến đi bắt đầu. Tài xế có thể hủy sau khi nhận chuyến và trước khi bắt đầu di chuyển. Bạn cần nhập lý do hủy.",
        ],
        [
          "Vì sao chưa nhận được chuyến mới?",
          "Tài xế cần có hồ sơ đã được duyệt, phương tiện đang hoạt động và bật nhận chuyến. Cho phép trình duyệt truy cập vị trí và giữ trang mở để cập nhật vị trí của bạn.",
        ],
        [
          "Thanh toán trên ứng dụng hoạt động thế nào?",
          "Ứng dụng hiện dùng thanh toán mô phỏng. Sau khi hoàn thành chuyến, trạng thái thanh toán được cập nhật theo kết quả xử lý. Nếu thất bại, hành khách có thể yêu cầu thử lại. Ứng dụng không thu tiền hoặc thông tin thẻ ngân hàng.",
        ],
        [
          "Bản đồ không hiển thị thì làm gì?",
          "Kiểm tra kết nối Internet và DNS của trình duyệt. Bạn vẫn có thể tìm địa chỉ và xem giá khi ảnh nền không tải được. Trên Brave, bạn có thể chọn Use secure DNS trong phần Security của cài đặt.",
        ],
        [
          "Tôi quên mật khẩu hoặc cần hỗ trợ tài khoản?",
          "Vui lòng liên hệ người quản lý hệ thống để được hỗ trợ. Ứng dụng hiện chưa có chức năng tự đặt lại mật khẩu.",
        ],
      ].map(([title, text]) => (
        <details className="card faq" key={title}>
          <summary>
            {title}
            <ChevronDown size={18} />
          </summary>
          <p>{text}</p>
        </details>
      ))}
      <p className="privacy-note">
        Bản đồ © OpenStreetMap contributors · Tìm địa điểm: Photon · Định tuyến:
        OSRM
      </p>
    </div>
  );
}
